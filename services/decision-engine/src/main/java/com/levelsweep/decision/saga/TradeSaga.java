package com.levelsweep.decision.saga;

import com.levelsweep.decision.fsm.session.SessionService;
import com.levelsweep.decision.fsm.session.SessionState;
import com.levelsweep.decision.fsm.trade.TradeEvent;
import com.levelsweep.decision.fsm.trade.TradeFsmInstance;
import com.levelsweep.decision.fsm.trade.TradeService;
import com.levelsweep.decision.risk.RiskService;
import com.levelsweep.decision.signal.SignalEvaluator;
import com.levelsweep.decision.strike.StrikeSelectorService;
import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Levels;
import com.levelsweep.shared.domain.options.OptionContract;
import com.levelsweep.shared.domain.options.StrikeSelection;
import com.levelsweep.shared.domain.options.StrikeSelectionResult;
import com.levelsweep.shared.domain.signal.SignalAction;
import com.levelsweep.shared.domain.signal.SignalEvaluation;
import com.levelsweep.shared.domain.trade.TradeProposed;
import com.levelsweep.shared.domain.trade.TradeSkipped;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 2 Step 6 orchestrator — bolts the Signal, Risk, Strike, and Session/Trade
 * FSM modules together into a coherent on-bar-close decision flow. Called by the
 * {@link com.levelsweep.decision.saga.SagaBarRouter} when a 2-min bar closes
 * with an indicator snapshot and reference levels available.
 *
 * <h2>Flow</h2>
 *
 * <ol>
 *   <li>Mint a {@code correlationId} for the saga run (UUID — pluggable supplier
 *       for replay determinism).
 *   <li>Read the tenant's {@link SessionState}. If not {@link SessionState#TRADING}
 *       fire {@link TradeSkipped} with stage {@code SESSION_NOT_TRADING} and stop.
 *   <li>Evaluate the signal via {@link SignalEvaluator}. If the verdict is
 *       {@link SignalAction#SKIP} fire {@link TradeSkipped} with stage
 *       {@code SIGNAL_SKIP} (carrying the reasons list) and stop.
 *   <li>Gate on the risk FSM via {@link RiskService#canTakeTrade}. If false fire
 *       {@link TradeSkipped} with stage {@code RISK_BLOCKED} and stop.
 *   <li>Resolve a 0DTE contract via {@link StrikeSelectorService#selectFor}. On
 *       {@link StrikeSelectionResult.NoCandidates} fire {@link TradeSkipped} with
 *       stage {@code NO_STRIKE} (reasonCode in the reasons list) and stop.
 *   <li>Drive the trade FSM PROPOSED → ENTERED via {@link TradeService#propose}
 *       and {@link TradeService#apply} with {@link TradeEvent#RISK_APPROVED}.
 *       Notify {@link RiskService#onTradeStarted} so the open-trade counter
 *       increments. Fire {@link TradeProposed} with all execution-ready fields.
 * </ol>
 *
 * <p>The saga does <b>not</b> place an Alpaca order — that's Phase 3. It produces
 * a self-describing {@link TradeProposed} event for downstream execution-service
 * consumers.
 *
 * <h2>Determinism</h2>
 *
 * <p>The saga is deterministic given (Clock, UUID supplier, bar, snapshot,
 * levels). The clock is injected via the standard CDI producer; the UUID
 * supplier is a constructor seam so the Phase 2 Step 7 replay-parity harness
 * can stub deterministic correlationIds. Production wires {@code UUID::randomUUID}
 * via a CDI producer.
 *
 * <h2>Thread-safety</h2>
 *
 * <p>The saga runs on the Kafka bar-consumer thread (the {@link SagaBarRouter}
 * calls in synchronously). Underlying services are either {@code @ApplicationScoped}
 * with their own internal synchronization (Risk, Trade, Session services use
 * concurrent maps or HashMap behind the bar-consumer single-thread invariant)
 * or stateless (Signal, Strike). The saga itself holds no mutable state.
 */
@ApplicationScoped
public class TradeSaga {

    private static final Logger LOG = LoggerFactory.getLogger(TradeSaga.class);

    /**
     * Tag value for the {@code decision.saga.evaluations.total} counter when the
     * saga produced a {@link TradeProposed}. Kept in sync with
     * {@code TradeSkipped} stage strings — the Prometheus dashboard pivots on
     * this single tag set.
     */
    static final String OUTCOME_PROPOSED = "PROPOSED";

    /** America/New_York — required for trading-session-date computation. */
    private static final java.time.ZoneId TRADING_ZONE = java.time.ZoneId.of("America/New_York");

    private final SessionService sessionService;
    private final SignalEvaluator signalEvaluator;
    private final RiskService riskService;
    private final StrikeSelectorService strikeSelectorService;
    private final TradeService tradeService;
    private final Clock clock;
    private final Event<TradeProposed> proposedEvent;
    private final Event<TradeSkipped> skippedEvent;
    private final MeterRegistry meterRegistry;
    private final Supplier<UUID> uuidSupplier;
    private final boolean enabled;
    private final String tenantId;

    /**
     * CDI / test constructor. The {@code uuidSupplier} is the seam tests use to
     * stub a deterministic correlationId for the Phase 2 Step 7 replay-parity
     * harness. Production wires {@code UUID::randomUUID} via the
     * {@link SagaUuidSupplier} CDI producer in the same package.
     */
    @Inject
    public TradeSaga(
            SessionService sessionService,
            SignalEvaluator signalEvaluator,
            RiskService riskService,
            StrikeSelectorService strikeSelectorService,
            TradeService tradeService,
            Clock clock,
            Event<TradeProposed> proposedEvent,
            Event<TradeSkipped> skippedEvent,
            MeterRegistry meterRegistry,
            Supplier<UUID> uuidSupplier,
            @ConfigProperty(name = "decision.saga.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "tenant.id", defaultValue = "OWNER") String tenantId) {
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
        this.signalEvaluator = Objects.requireNonNull(signalEvaluator, "signalEvaluator");
        this.riskService = Objects.requireNonNull(riskService, "riskService");
        this.strikeSelectorService = Objects.requireNonNull(strikeSelectorService, "strikeSelectorService");
        this.tradeService = Objects.requireNonNull(tradeService, "tradeService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.proposedEvent = Objects.requireNonNull(proposedEvent, "proposedEvent");
        this.skippedEvent = Objects.requireNonNull(skippedEvent, "skippedEvent");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier");
        this.enabled = enabled;
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
    }

    /**
     * Run the saga for a single (bar, snapshot, levels) tuple. Synchronous —
     * the caller (Kafka bar-consumer thread) blocks until the result is computed
     * and any CDI events are delivered.
     */
    public Result run(Bar bar, IndicatorSnapshot snapshot, Levels levels) {
        Objects.requireNonNull(bar, "bar");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(levels, "levels");

        if (!enabled) {
            // Emergency disable — short-circuit before doing anything observable.
            // We deliberately do not record a counter here; the absence of any
            // saga.evaluations.total increments is the operator's signal.
            LOG.warn("trade-saga disabled via decision.saga.enabled=false; skipping bar closeTime={}", bar.closeTime());
            return new Result.Skipped(buildSkipped(bar, levels, "SAGA_DISABLED", List.of("disabled_by_config"), "n/a"));
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        String correlationId = uuidSupplier.get().toString();
        try {
            // ---- 1. Session gate -------------------------------------------
            SessionState session = sessionService.currentState(tenantId);
            if (session != SessionState.TRADING) {
                TradeSkipped skipped = buildSkipped(
                        bar,
                        levels,
                        TradeSkipped.STAGE_SESSION_NOT_TRADING,
                        List.of("session_state:" + session.name()),
                        correlationId);
                return finishSkipped(skipped, sample);
            }

            // ---- 2. Signal evaluation --------------------------------------
            SignalEvaluation eval = signalEvaluator.evaluate(bar, snapshot, levels);
            if (eval.action() == SignalAction.SKIP) {
                TradeSkipped skipped =
                        buildSkipped(bar, levels, TradeSkipped.STAGE_SIGNAL_SKIP, eval.reasons(), correlationId);
                return finishSkipped(skipped, sample);
            }

            // ---- 3. Risk gate ----------------------------------------------
            if (!riskService.canTakeTrade(tenantId)) {
                TradeSkipped skipped = buildSkipped(
                        bar,
                        levels,
                        TradeSkipped.STAGE_RISK_BLOCKED,
                        List.of("risk_state:"
                                + riskService
                                        .snapshot(tenantId)
                                        .map(s -> s.state().name())
                                        .orElse("UNINITIALIZED")),
                        correlationId);
                return finishSkipped(skipped, sample);
            }

            // ---- 4. Strike selection ---------------------------------------
            // The saga runs at bar-close so the trading session date is the bar's
            // close time mapped to America/New_York. Using the bar's clock keeps
            // the saga deterministic with the input — replay reads the same
            // closeTime and computes the same date.
            LocalDate sessionDate = bar.closeTime().atZone(TRADING_ZONE).toLocalDate();
            StrikeSelectionResult selectionResult = strikeSelectorService.selectFor(
                    bar.symbol(), bar.close(), eval.optionSide().orElseThrow(), sessionDate);
            if (selectionResult instanceof StrikeSelectionResult.NoCandidates noCandidates) {
                TradeSkipped skipped = buildSkipped(
                        bar, levels, TradeSkipped.STAGE_NO_STRIKE, List.of(noCandidates.reasonCode()), correlationId);
                return finishSkipped(skipped, sample);
            }
            StrikeSelection selection = ((StrikeSelectionResult.Selected) selectionResult).selection();
            OptionContract contract = selection.chosen();

            // ---- 5. Drive trade FSM PROPOSED → ENTERED ---------------------
            TradeFsmInstance proposed = tradeService.propose(tenantId, sessionDate);
            // Tell the risk FSM a trade started — increments openTrades (used to
            // gate the per-day max-trades cap in S3).
            riskService.onTradeStarted(tenantId);
            Optional<TradeFsmInstance> entered =
                    tradeService.apply(proposed.tradeId(), TradeEvent.RISK_APPROVED, Optional.of(correlationId));
            if (entered.isEmpty()) {
                // Should not happen — PROPOSED + RISK_APPROVED is a defined edge in
                // TradeFsm. If it does, surface as a skip with a diagnostic stage.
                LOG.error(
                        "trade-saga FSM rejected RISK_APPROVED tradeId={} state={} — bug in TradeFsm or stale state",
                        proposed.tradeId(),
                        proposed.state());
                TradeSkipped skipped = buildSkipped(
                        bar,
                        levels,
                        "FSM_REJECT",
                        List.of("trade_fsm_rejected_risk_approved", "tradeId:" + proposed.tradeId()),
                        correlationId);
                return finishSkipped(skipped, sample);
            }

            // ---- 6. Build + fire TradeProposed -----------------------------
            Instant proposedAt = proposed.proposedAt().orElseGet(clock::instant);
            TradeProposed event = new TradeProposed(
                    tenantId,
                    proposed.tradeId(),
                    sessionDate,
                    proposedAt,
                    bar.symbol(),
                    eval.optionSide().orElseThrow(),
                    contract.symbol(),
                    contract.bidPrice(),
                    contract.askPrice(),
                    contract.mid(),
                    contract.impliedVolatility(),
                    contract.delta(),
                    correlationId,
                    eval.reasons());
            proposedEvent.fire(event);
            meterRegistry
                    .counter("decision.saga.evaluations.total", Tags.of("outcome", OUTCOME_PROPOSED))
                    .increment();
            sample.stop(meterRegistry.timer("decision.saga.duration", Tags.of("outcome", OUTCOME_PROPOSED)));
            LOG.info(
                    "trade-saga proposed tenant={} tradeId={} symbol={} side={} contract={} correlationId={} reasons={}",
                    tenantId,
                    proposed.tradeId(),
                    bar.symbol(),
                    event.side(),
                    contract.symbol(),
                    correlationId,
                    eval.reasons());
            return new Result.Proposed(event);
        } catch (RuntimeException ex) {
            // Any unexpected failure is an internal saga bug — record a skip with
            // a SAGA_ERROR stage so the audit trail still has the bar covered, then
            // let the exception propagate to the caller's logger. The CDI event is
            // best-effort: if firing the skip raises another exception we suppress
            // it via addSuppressed so the original cause stays primary.
            try {
                TradeSkipped err = buildSkipped(
                        bar,
                        levels,
                        "SAGA_ERROR",
                        List.of("exception:" + ex.getClass().getSimpleName()),
                        correlationId);
                skippedEvent.fire(err);
                meterRegistry
                        .counter("decision.saga.evaluations.total", Tags.of("outcome", "SAGA_ERROR"))
                        .increment();
            } catch (RuntimeException nested) {
                ex.addSuppressed(nested);
            }
            sample.stop(meterRegistry.timer("decision.saga.duration", Tags.of("outcome", "SAGA_ERROR")));
            throw ex;
        }
    }

    private TradeSkipped buildSkipped(
            Bar bar, Levels levels, String stage, List<String> reasons, String correlationId) {
        return new TradeSkipped(
                levels.tenantId(), levels.sessionDate(), bar.closeTime(), stage, reasons, correlationId);
    }

    private Result finishSkipped(TradeSkipped skipped, Timer.Sample sample) {
        skippedEvent.fire(skipped);
        meterRegistry
                .counter("decision.saga.evaluations.total", Tags.of("outcome", "SKIPPED", "stage", skipped.stage()))
                .increment();
        sample.stop(
                meterRegistry.timer("decision.saga.duration", Tags.of("outcome", "SKIPPED", "stage", skipped.stage())));
        if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "trade-saga skipped tenant={} stage={} correlationId={} reasons={}",
                    skipped.tenantId(),
                    skipped.stage(),
                    skipped.correlationId(),
                    skipped.reasons());
        }
        return new Result.Skipped(skipped);
    }

    /**
     * Sealed result — Trade Saga either proposed a trade (and emitted a
     * {@link TradeProposed}) or skipped (and emitted a {@link TradeSkipped}).
     * Returned by {@link #run} so callers (router, tests) can pattern-match
     * exhaustively without needing to listen to the CDI events.
     */
    public sealed interface Result {
        record Proposed(TradeProposed event) implements Result {}

        record Skipped(TradeSkipped event) implements Result {}
    }
}
