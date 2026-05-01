package com.levelsweep.decision.fsm.trade;

import com.levelsweep.decision.fsm.persistence.FsmTransitionRepository;
import com.levelsweep.shared.fsm.FsmTransition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service-level coordinator for the {@link TradeFsm}. Holds the per-trade
 * {@link TradeFsmInstance} in memory keyed by {@code tradeId}, calls the pure FSM,
 * and persists every accepted transition through {@link FsmTransitionRepository}.
 *
 * <p>Phase 2 keeps the in-memory map. Phase 3 Trade Saga either replaces this with
 * a Kafka-driven actor or leaves the in-memory map as the hot-path cache and writes
 * through to the {@code trades} MS SQL table per architecture-spec §13.1.
 */
@ApplicationScoped
public class TradeService {

    private static final Logger LOG = LoggerFactory.getLogger(TradeService.class);

    private final TradeFsm fsm;
    private final FsmTransitionRepository repository;
    private final Clock clock;

    private final Map<String, TradeFsmInstance> trades = new HashMap<>();

    @Inject
    public TradeService(TradeFsm fsm, FsmTransitionRepository repository, Clock clock) {
        this.fsm = Objects.requireNonNull(fsm, "fsm");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Boot a new trade in the {@link TradeState#PROPOSED} state.
     *
     * <p>No seed transition is persisted — the first {@code fsm_transitions} row for
     * this trade will be the PROPOSED → ENTERED edge on {@code RISK_APPROVED}.
     * Replay finds the trade's earliest row and reconstructs the implicit PROPOSED
     * boot via {@code from_state}.
     */
    public TradeFsmInstance propose(String tenantId, LocalDate sessionDate) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sessionDate, "sessionDate");
        String tradeId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        TradeFsmInstance instance = new TradeFsmInstance(
                tenantId,
                sessionDate,
                tradeId,
                TradeState.PROPOSED,
                Optional.empty(),
                Optional.of(now),
                Optional.empty(),
                Optional.empty());
        trades.put(tradeId, instance);
        return instance;
    }

    /** Returns the current snapshot for {@code tradeId}, if any. */
    public Optional<TradeFsmInstance> find(String tradeId) {
        return Optional.ofNullable(trades.get(tradeId));
    }

    /**
     * Apply an event to the trade. Returns the new instance if the event was legal,
     * or {@link Optional#empty()} otherwise. Persists every accepted transition.
     */
    public Optional<TradeFsmInstance> apply(String tradeId, TradeEvent event, Optional<String> correlationId) {
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(event, "event");
        TradeFsmInstance current = trades.get(tradeId);
        if (current == null) {
            LOG.debug("apply on unknown tradeId={}", tradeId);
            return Optional.empty();
        }
        Optional<TradeState> next = fsm.next(current.state(), event);
        if (next.isEmpty()) {
            LOG.debug("invalid transition tradeId={} state={} event={}", tradeId, current.state(), event);
            return Optional.empty();
        }
        TradeState after = next.get();
        TradeFsmInstance updated = current.withState(after);
        trades.put(tradeId, updated);
        persist(current, updated, event, correlationId);
        return Optional.of(updated);
    }

    private void persist(
            TradeFsmInstance from, TradeFsmInstance to, TradeEvent event, Optional<String> correlationId) {
        FsmTransition<TradeState, TradeEvent> transition = new FsmTransition<>(
                to.tenantId(),
                to.sessionDate(),
                fsm.fsmKind(),
                to.tradeId(),
                fsm.fsmVersion(),
                Optional.of(from.state()),
                to.state(),
                event,
                clock.instant(),
                Optional.empty(),
                correlationId);
        repository.record(transition);
    }

}
