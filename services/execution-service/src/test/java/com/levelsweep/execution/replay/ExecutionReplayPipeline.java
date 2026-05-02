package com.levelsweep.execution.replay;

import com.levelsweep.execution.ingest.TradeProposedConsumer;
import com.levelsweep.shared.domain.trade.TradeProposed;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Test-only composition that drives a hand-labeled {@link ExecutionScenarios.ExecutionScenario}
 * through the parts of the Phase 3 execution-service pipeline that exist on
 * {@code main} today — the {@link TradeProposedConsumer} + {@link RecordingTradeRouter}
 * pair from S1 (PR #77).
 *
 * <p>Mirrors decision-engine's {@code DecisionReplayPipeline} pattern: hand-instantiated
 * beans (no CDI), pure synchronous call sequence per scenario, all output
 * captured into immutable lists. Per-event behaviour for the still-merging
 * sub-agents (S2 Alpaca client, S3 fill listener, S5 trail manager, S6 EOD
 * flatten) is structured as TODO seams that future PRs flesh out without
 * rewriting the harness shape.
 *
 * <h3>Determinism contract</h3>
 *
 * <ul>
 *   <li>No {@link java.time.Instant#now()} — every timestamp comes from the
 *       {@link ExecutionScenarios} fixture instants.
 *   <li>No randomness — fixtures are hand-picked literals.
 *   <li>No external IO — Mongo / MS SQL / Kafka / Anthropic / Alpaca are absent
 *       from the test classpath of this harness by design (per the
 *       {@code replay-parity} skill).
 *   <li>Records use structural equality — the byte-equal assert in
 *       {@code ExecutionReplayHarnessTest#deterministicAcrossRuns} relies on
 *       {@link TradeProposed} (and every other captured record) being a
 *       value-typed record.
 * </ul>
 *
 * <h3>What's wired today</h3>
 *
 * <ol>
 *   <li>{@link TradeProposedConsumer#consume} → {@link RecordingTradeRouter}
 *       captures the event.
 *   <li>Each {@link SimulatedEvent} dispatches to a dedicated handler that
 *       appends to a typed capture list. Handlers are no-op stubs today;
 *       wiring lands when S2/S3/S5/S6 merge.
 * </ol>
 */
public final class ExecutionReplayPipeline {

    private final RecordingTradeRouter router;
    private final TradeProposedConsumer consumer;

    private final List<SimulatedEvent.OrderSubmissionResponse> orderResponses = new ArrayList<>();
    private final List<SimulatedEvent.FillFrame> fillFrames = new ArrayList<>();
    private final List<SimulatedEvent.StopBreach> stopBreaches = new ArrayList<>();
    private final List<SimulatedEvent.EodTrigger> eodTriggers = new ArrayList<>();

    public ExecutionReplayPipeline() {
        this.router = new RecordingTradeRouter();
        this.consumer = new TradeProposedConsumer(router);
    }

    /**
     * Drive a single scenario through the pipeline: feed the proposed event
     * to the consumer, then dispatch each simulated event in order.
     */
    public void onScenario(ExecutionScenarios.ExecutionScenario scenario) {
        Objects.requireNonNull(scenario, "scenario");
        consumer.consume(scenario.input());
        for (SimulatedEvent event : scenario.events()) {
            dispatch(event);
        }
    }

    /**
     * Per-event dispatch. Today each handler simply records the event for
     * downstream assertions; once S2/S3/S5/S6 land, follow-up PRs will replace
     * the bodies with calls into the real production routers.
     */
    private void dispatch(SimulatedEvent event) {
        // Pattern-match dispatch — one branch per SimulatedEvent variant.
        switch (event) {
            case SimulatedEvent.OrderSubmissionResponse osr -> orderResponses.add(osr);
            case SimulatedEvent.FillFrame ff -> fillFrames.add(ff);
            case SimulatedEvent.StopBreach sb -> stopBreaches.add(sb);
            case SimulatedEvent.EodTrigger et -> eodTriggers.add(et);
        }
    }

    /** All {@link TradeProposed} events the consumer forwarded to the router. */
    public List<TradeProposed> capturedTrades() {
        return router.captured();
    }

    /** All synthetic order-submission responses observed by the harness. */
    public List<SimulatedEvent.OrderSubmissionResponse> capturedOrderResponses() {
        return List.copyOf(orderResponses);
    }

    /** All synthetic fill frames observed by the harness. */
    public List<SimulatedEvent.FillFrame> capturedFills() {
        return List.copyOf(fillFrames);
    }

    /** All synthetic stop-breach events observed by the harness. */
    public List<SimulatedEvent.StopBreach> capturedStopBreaches() {
        return List.copyOf(stopBreaches);
    }

    /** All synthetic EOD triggers observed by the harness. */
    public List<SimulatedEvent.EodTrigger> capturedEodTriggers() {
        return List.copyOf(eodTriggers);
    }
}
