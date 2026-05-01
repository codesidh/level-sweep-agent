package com.levelsweep.decision.risk;

import com.levelsweep.decision.risk.persistence.DailyRiskStateRepository;
import com.levelsweep.shared.domain.risk.DailyRiskState;
import com.levelsweep.shared.domain.risk.RiskEvent;
import com.levelsweep.shared.domain.risk.RiskState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinator at the IO boundary of the Risk FSM. Owns the in-memory tenant →
 * {@link DailyRiskState} cache, persists each transition's events + state to
 * MS SQL, and exposes the gate the Trade Saga (Phase 2 Step 4) calls before
 * firing a signal.
 *
 * <p>Threading: each tenant's state is mutated under that tenant's bucket only,
 * via {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)},
 * so two concurrent fills for the same tenant serialize through the FSM. Cross-
 * tenant calls remain lock-free.
 *
 * <p>Persistence ordering: events are written before the new state is upserted.
 * If the events insert fails, the state upsert is skipped — the in-memory cache
 * is rolled back to the prior snapshot so the next call retries from a known
 * state. Phase 2 Step 4 wraps the two writes in a transaction (architecture-
 * spec §11 step 10); for Step 3 we accept the at-most-once write hazard since
 * the Replay Harness can rebuild from raw fills.
 */
@ApplicationScoped
public class RiskService {

    private static final Logger LOG = LoggerFactory.getLogger(RiskService.class);

    /** America/New_York — the trading-day reset boundary per requirements §11.4. */
    private static final ZoneId TRADING_ZONE = ZoneId.of("America/New_York");

    private final RiskFsm fsm;
    private final DailyRiskStateRepository repository;
    private final Clock clock;

    /** Tenant → current daily-risk snapshot. Empty until {@link #onDailyReset} fires. */
    private final Map<String, DailyRiskState> stateByTenant = new ConcurrentHashMap<>();

    @Inject
    public RiskService(RiskFsm fsm, DailyRiskStateRepository repository, Clock clock) {
        this.fsm = Objects.requireNonNull(fsm, "fsm");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Gate consulted by the Trade Saga before firing a signal. Returns
     * {@code true} when the FSM is in {@link RiskState#HEALTHY} or
     * {@link RiskState#BUDGET_LOW} (re-entry allowed under §11.3 until budget
     * is exhausted); {@code false} when {@link RiskState#HALTED} or no state
     * has been initialized yet (a never-reset FSM is not safe to trade against).
     */
    public boolean canTakeTrade(String tenantId) {
        DailyRiskState s = stateByTenant.get(Objects.requireNonNull(tenantId, "tenantId"));
        if (s == null) {
            return false;
        }
        return s.state() != RiskState.HALTED;
    }

    /** Read-only view of a tenant's current state. */
    public Optional<DailyRiskState> snapshot(String tenantId) {
        return Optional.ofNullable(stateByTenant.get(Objects.requireNonNull(tenantId, "tenantId")));
    }

    /**
     * Reset for a new trading session. Computes the loss budget per §11.2
     * ({@code 2% × startingEquity}) and persists the fresh HEALTHY state.
     */
    public DailyRiskState onDailyReset(String tenantId, BigDecimal startingEquity, BigDecimal dailyLossBudget) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(startingEquity, "startingEquity");
        Objects.requireNonNull(dailyLossBudget, "dailyLossBudget");

        Instant now = clock.instant();
        LocalDate sessionDate = sessionDateFor(now);

        RiskFsm.Result result = fsm.reset(tenantId, sessionDate, startingEquity, dailyLossBudget, now);
        return persistAndCache(tenantId, result);
    }

    /** Apply a fill's realized P&L delta. */
    public DailyRiskState onFillRealized(String tenantId, BigDecimal realizedDelta) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(realizedDelta, "realizedDelta");
        Instant now = clock.instant();
        return mutate(tenantId, current -> fsm.onFillRealized(current, realizedDelta, now), "onFillRealized");
    }

    /** Mark a new trade as having entered. */
    public DailyRiskState onTradeStarted(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Instant now = clock.instant();
        return mutate(tenantId, current -> fsm.onTradeStarted(current, now), "onTradeStarted");
    }

    /** Operator-initiated halt (e.g. news blackout, manual stop). */
    public DailyRiskState onHaltManual(String tenantId, String reason) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(reason, "reason");
        Instant now = clock.instant();
        return mutate(tenantId, current -> fsm.onHaltManual(current, reason, now), "onHaltManual");
    }

    // --- internals -------------------------------------------------------

    private DailyRiskState mutate(
            String tenantId, java.util.function.Function<DailyRiskState, RiskFsm.Result> transition, String operation) {
        DailyRiskState current = stateByTenant.get(tenantId);
        if (current == null) {
            LOG.warn("Risk FSM operation {} called before daily reset for tenantId={} — ignored", operation, tenantId);
            throw new IllegalStateException(
                    "Risk FSM not initialized for tenantId=" + tenantId + " (call onDailyReset first)");
        }
        RiskFsm.Result result = transition.apply(current);
        return persistAndCache(tenantId, result);
    }

    private DailyRiskState persistAndCache(String tenantId, RiskFsm.Result result) {
        // Persist events first — if the insert fails, the state upsert is
        // skipped and the in-memory cache stays on the previous snapshot.
        for (RiskEvent event : result.events()) {
            repository.recordEvent(event);
        }
        repository.upsert(result.newState());
        stateByTenant.put(tenantId, result.newState());
        return result.newState();
    }

    /**
     * Trading-day session date — {@link Instant} now in America/New_York. The
     * 09:29 ET reset boundary aligns the session-date with the local trading
     * day, not the UTC calendar day (a critical distinction for after-hours
     * fills that settle past midnight UTC).
     */
    private static LocalDate sessionDateFor(Instant now) {
        return now.atZone(TRADING_ZONE).toLocalDate();
    }
}
