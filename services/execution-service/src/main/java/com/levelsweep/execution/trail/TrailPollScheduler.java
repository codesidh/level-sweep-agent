package com.levelsweep.execution.trail;

import com.levelsweep.execution.persistence.TrailAuditRepository;
import com.levelsweep.shared.domain.trade.TradeTrailBreached;
import com.levelsweep.shared.domain.trade.TradeTrailRatcheted;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 3 Step 5 trail poll loop. Per ADR-0005 §5 + ADR-0004, runs every
 * 1 second; for each registered trail state it asks
 * {@link AlpacaQuotesClient} for the latest NBBO snapshot, computes the
 * mid-point, and advances the FSM via {@link TrailStateMachine#advance}.
 *
 * <p>On each {@link TrailStateMachine.Decision} variant:
 *
 * <ul>
 *   <li>{@code Inactive} / {@code Holding} — no audit, no event.
 *   <li>{@code Armed} / {@code Ratcheted} — write {@code RATCHET} audit row,
 *       fire {@link TradeTrailRatcheted} (audit-only event).
 *   <li>{@code ExitTriggered} — write {@code EXIT} audit row, fire
 *       {@link TradeTrailBreached} (consumed by {@code ExitOrderRouter}).
 *       Deregister the trade from {@link TrailRegistry} so the next tick
 *       does not re-fire.
 * </ul>
 *
 * <p>Quote-fetch failures are passed through silently — an empty
 * {@link Optional} from the client means "no fresh NBBO this tick", logged
 * once-per-tick at DEBUG. The next tick retries; the FSM never advances on
 * a missing snapshot (fail-closed).
 *
 * <p>Determinism: every clock read happens on the
 * {@link AlpacaQuotesClient.NbboSnapshot#timestamp()} carried by the
 * snapshot itself — the scheduler does NOT call {@code Instant.now()}.
 * Replay harness can drive the FSM by feeding canned snapshots through a
 * stubbed {@link AlpacaQuotesClient}.
 */
@ApplicationScoped
public class TrailPollScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(TrailPollScheduler.class);

    private final TrailRegistry registry;
    private final TrailConfig config;
    private final AlpacaQuotesClient quotes;
    private final TrailAuditRepository audit;
    private final Event<TradeTrailRatcheted> ratchetEvent;
    private final Event<TradeTrailBreached> breachEvent;

    @Inject
    public TrailPollScheduler(
            TrailRegistry registry,
            TrailConfig config,
            AlpacaQuotesClient quotes,
            TrailAuditRepository audit,
            Event<TradeTrailRatcheted> ratchetEvent,
            Event<TradeTrailBreached> breachEvent) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.config = Objects.requireNonNull(config, "config");
        this.quotes = Objects.requireNonNull(quotes, "quotes");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ratchetEvent = Objects.requireNonNull(ratchetEvent, "ratchetEvent");
        this.breachEvent = Objects.requireNonNull(breachEvent, "breachEvent");
    }

    /**
     * Cron handler — fires every 1 second. Delegates to {@link #pollOnce()}
     * so unit tests can drive the loop directly.
     */
    @Scheduled(every = "1s", identity = "trail-poll")
    void onTick() {
        pollOnce();
    }

    /** Test-visible body. One tick over every registered trail. */
    public void pollOnce() {
        for (TrailState state : registry.snapshot()) {
            try {
                step(state);
            } catch (RuntimeException e) {
                // Defensive — an exception advancing one trade's state must
                // not stop the poll loop from servicing the rest of the
                // registry. Log + carry on.
                LOG.warn(
                        "trail poll: exception advancing state tenantId={} tradeId={}: {}",
                        state.tenantId(),
                        state.tradeId(),
                        e.toString());
            }
        }
    }

    private void step(TrailState state) {
        Optional<AlpacaQuotesClient.NbboSnapshot> snap = quotes.snapshot(state.contractSymbol());
        if (snap.isEmpty()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(
                        "trail poll: no fresh NBBO tenantId={} tradeId={} contractSymbol={}",
                        state.tenantId(),
                        state.tradeId(),
                        state.contractSymbol());
            }
            return;
        }
        AlpacaQuotesClient.NbboSnapshot s = snap.get();
        BigDecimal mid = s.mid();
        Instant ts = s.timestamp();

        TrailStateMachine.Decision decision = TrailStateMachine.advance(state, mid, ts, config);
        switch (decision) {
            case TrailStateMachine.Decision.Inactive ig -> {
                /* no-op */
            }
            case TrailStateMachine.Decision.Holding h -> {
                /* no-op */
            }
            case TrailStateMachine.Decision.Armed a -> emitRatchet(state, mid, ts, decision.uplPct(), a.newFloor());
            case TrailStateMachine.Decision.Ratcheted r -> emitRatchet(state, mid, ts, decision.uplPct(), r.newFloor());
            case TrailStateMachine.Decision.ExitTriggered ex -> emitExit(state, mid, ts, ex.exitFloor());
        }
    }

    private void emitRatchet(TrailState state, BigDecimal mid, Instant ts, BigDecimal uplPct, BigDecimal newFloor) {
        TradeTrailRatcheted evt = new TradeTrailRatcheted(
                state.tenantId(), state.tradeId(), ts, mid, uplPct, newFloor, state.correlationId());
        try {
            audit.recordRatchet(evt, state.contractSymbol());
        } catch (RuntimeException e) {
            LOG.warn(
                    "trail poll: ratchet audit failed tenantId={} tradeId={} reason={}",
                    state.tenantId(),
                    state.tradeId(),
                    e.toString());
        }
        LOG.info(
                "trail ratcheted tenantId={} tradeId={} contractSymbol={} uplPct={} newFloorPct={} nbboMid={}",
                state.tenantId(),
                state.tradeId(),
                state.contractSymbol(),
                uplPct,
                newFloor,
                mid);
        ratchetEvent.fire(evt);
    }

    private void emitExit(TrailState state, BigDecimal mid, Instant ts, BigDecimal exitFloor) {
        TradeTrailBreached evt = new TradeTrailBreached(
                state.tenantId(), state.tradeId(), state.contractSymbol(), ts, mid, exitFloor, state.correlationId());
        try {
            audit.recordExit(evt);
        } catch (RuntimeException e) {
            LOG.warn(
                    "trail poll: exit audit failed tenantId={} tradeId={} reason={}",
                    state.tenantId(),
                    state.tradeId(),
                    e.toString());
        }
        LOG.info(
                "trail breached tenantId={} tradeId={} contractSymbol={} exitFloorPct={} nbboMid={}",
                state.tenantId(),
                state.tradeId(),
                state.contractSymbol(),
                exitFloor,
                mid);

        // Deregister BEFORE firing — synchronous CDI observers (ExitOrderRouter)
        // see a clean registry and a future tick on the same trade is a no-op
        // even if the CDI dispatch races with the next @Scheduled fire.
        registry.deregister(state.tradeId());
        breachEvent.fire(evt);
    }
}
