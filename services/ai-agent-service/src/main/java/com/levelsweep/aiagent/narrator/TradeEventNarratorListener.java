package com.levelsweep.aiagent.narrator;

import com.levelsweep.shared.domain.trade.TradeEodFlattened;
import com.levelsweep.shared.domain.trade.TradeFilled;
import com.levelsweep.shared.domain.trade.TradeOrderRejected;
import com.levelsweep.shared.domain.trade.TradeOrderSubmitted;
import com.levelsweep.shared.domain.trade.TradeStopTriggered;
import com.levelsweep.shared.domain.trade.TradeTrailBreached;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges the six Phase 3 trade lifecycle events into the {@link TradeNarrator}.
 *
 * <p><b>Event sourcing — Phase 4 reality</b>:
 *
 * <ul>
 *   <li>{@link TradeFilled} — published to the {@code tenant.fills} Kafka topic
 *       by execution-service (architecture-spec §12.1; see
 *       {@code com.levelsweep.execution.messaging.TradeFilledKafkaPublisher}).
 *       The narrator service consumes via {@code @Incoming("trade-fills-in")}.
 *       This is the primary cross-service path and the only one wired through
 *       Kafka in Phase 4.</li>
 *   <li>{@link TradeOrderSubmitted}, {@link TradeOrderRejected},
 *       {@link TradeStopTriggered}, {@link TradeTrailBreached},
 *       {@link TradeEodFlattened} — fired in execution-service's JVM via the
 *       in-process CDI {@code Event<T>} bus, but NOT yet published to Kafka.
 *       In production these never reach ai-agent-service's JVM (they live in
 *       a different pod). The {@code @Observes} listeners below exist for two
 *       reasons:
 *       <ol>
 *         <li>Integration tests (or a future co-located build) can fire them
 *             in-process and exercise the narrator path.</li>
 *         <li>Documents the gap so a Phase 5/6 follow-up can stand up the
 *             cross-service topics ({@code tenant.events.exit_*} per
 *             architecture-spec §12.1) without changing this class — only the
 *             producer side moves.</li>
 *       </ol>
 *   </li>
 * </ul>
 *
 * <p><b>Failure posture — async + non-blocking</b>: every method swallows
 * exceptions and logs WARN. The trade FSM and saga MUST NEVER block on the
 * narrator (CLAUDE.md guardrail #3 + architecture-spec §4.4 — "agent CANNOT
 * mutate Trade FSM"). A failed narration is never propagated.
 *
 * <p>The {@link TradeNarrator} itself returns {@link Optional#empty()} on
 * cost-cap breach + every Anthropic failure mode; the listener only writes to
 * Mongo when a narrative is actually present.
 */
@ApplicationScoped
public class TradeEventNarratorListener {

    private static final Logger LOG = LoggerFactory.getLogger(TradeEventNarratorListener.class);

    private final TradeNarrator narrator;
    private final TradeNarrativeRepository repository;

    @Inject
    public TradeEventNarratorListener(TradeNarrator narrator, TradeNarrativeRepository repository) {
        this.narrator = Objects.requireNonNull(narrator, "narrator");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    // ---------------------------------------------------------------------
    // Kafka @Incoming — the only cross-service path in Phase 4. Subscribed to
    // the tenant.fills topic published by execution-service (Phase 3 S3).
    // ---------------------------------------------------------------------

    /**
     * Kafka consumer for the {@code tenant.fills} topic. The configured
     * deserializer is {@link TradeFilledDeserializer}. Quarkus dispatches each
     * message on a Vert.x worker thread; the narrator's HTTP call is allowed
     * to block.
     */
    @Incoming("trade-fills-in")
    public void onTradeFilledKafka(TradeFilled event) {
        if (event == null) {
            return;
        }
        try {
            NarrationRequest request = new NarrationRequest(
                    event.tenantId(),
                    NarrationPromptBuilder.EVENT_FILL,
                    fillPayload(event),
                    event.tradeId(),
                    event.filledAt());
            invoke(request);
        } catch (RuntimeException e) {
            LOG.warn(
                    "narrator listener swallowed exception on TradeFilled tenantId={} tradeId={}: {}",
                    event.tenantId(),
                    event.tradeId(),
                    e.toString());
        }
    }

    // ---------------------------------------------------------------------
    // CDI @Observes — in-process listeners for the events that do NOT (yet)
    // travel via Kafka. In production these don't fire in the ai-agent-service
    // JVM; they're here so integration tests + a future co-located build can
    // exercise the path. Phase 5/6 follow-up: stand up the cross-service
    // topics so these fire in production too.
    // ---------------------------------------------------------------------

    public void onTradeOrderSubmitted(@Observes TradeOrderSubmitted event) {
        if (event == null) {
            return;
        }
        try {
            NarrationRequest request = new NarrationRequest(
                    event.tenantId(),
                    NarrationPromptBuilder.EVENT_ORDER_SUBMITTED,
                    orderSubmittedPayload(event),
                    event.tradeId(),
                    event.submittedAt());
            invoke(request);
        } catch (RuntimeException e) {
            LOG.warn(
                    "narrator listener swallowed exception on TradeOrderSubmitted tenantId={} tradeId={}: {}",
                    event.tenantId(),
                    event.tradeId(),
                    e.toString());
        }
    }

    public void onTradeOrderRejected(@Observes TradeOrderRejected event) {
        if (event == null) {
            return;
        }
        try {
            NarrationRequest request = new NarrationRequest(
                    event.tenantId(),
                    NarrationPromptBuilder.EVENT_REJECTED,
                    rejectedPayload(event),
                    event.tradeId(),
                    event.rejectedAt());
            invoke(request);
        } catch (RuntimeException e) {
            LOG.warn(
                    "narrator listener swallowed exception on TradeOrderRejected tenantId={} tradeId={}: {}",
                    event.tenantId(),
                    event.tradeId(),
                    e.toString());
        }
    }

    public void onTradeStopTriggered(@Observes TradeStopTriggered event) {
        if (event == null) {
            return;
        }
        try {
            NarrationRequest request = new NarrationRequest(
                    event.tenantId(),
                    NarrationPromptBuilder.EVENT_STOP,
                    stopPayload(event),
                    event.tradeId(),
                    event.triggeredAt());
            invoke(request);
        } catch (RuntimeException e) {
            LOG.warn(
                    "narrator listener swallowed exception on TradeStopTriggered tenantId={} tradeId={}: {}",
                    event.tenantId(),
                    event.tradeId(),
                    e.toString());
        }
    }

    public void onTradeTrailBreached(@Observes TradeTrailBreached event) {
        if (event == null) {
            return;
        }
        try {
            NarrationRequest request = new NarrationRequest(
                    event.tenantId(),
                    NarrationPromptBuilder.EVENT_TRAIL_BREACH,
                    trailBreachPayload(event),
                    event.tradeId(),
                    event.observedAt());
            invoke(request);
        } catch (RuntimeException e) {
            LOG.warn(
                    "narrator listener swallowed exception on TradeTrailBreached tenantId={} tradeId={}: {}",
                    event.tenantId(),
                    event.tradeId(),
                    e.toString());
        }
    }

    public void onTradeEodFlattened(@Observes TradeEodFlattened event) {
        if (event == null) {
            return;
        }
        try {
            NarrationRequest request = new NarrationRequest(
                    event.tenantId(),
                    NarrationPromptBuilder.EVENT_EOD_FLATTEN,
                    eodFlattenPayload(event),
                    event.tradeId(),
                    event.flattenedAt());
            invoke(request);
        } catch (RuntimeException e) {
            LOG.warn(
                    "narrator listener swallowed exception on TradeEodFlattened tenantId={} tradeId={}: {}",
                    event.tenantId(),
                    event.tradeId(),
                    e.toString());
        }
    }

    /**
     * Common path: call the narrator and persist the resulting narrative (if
     * any). Wraps the repository call in its own try/catch so a Mongo write
     * failure does not propagate up the listener path.
     */
    private void invoke(NarrationRequest request) {
        Optional<TradeNarrative> result;
        try {
            result = narrator.narrate(request);
        } catch (RuntimeException e) {
            LOG.warn(
                    "narrator threw on tenantId={} tradeId={} eventType={}: {}",
                    request.tenantId(),
                    request.tradeId(),
                    request.eventType(),
                    e.toString());
            return;
        }
        if (result.isEmpty()) {
            return;
        }
        try {
            repository.save(result.get(), request.eventType());
        } catch (RuntimeException e) {
            LOG.warn(
                    "narrator repository.save threw tenantId={} tradeId={} eventType={}: {}",
                    request.tenantId(),
                    request.tradeId(),
                    request.eventType(),
                    e.toString());
        }
    }

    // ---------------------------------------------------------------------
    // Payload mappers — extract the fields each prompt template needs into a
    // compact key=value string. Stable across runs, no clock reads, no UUIDs
    // (replay-parity discipline).
    // ---------------------------------------------------------------------

    static String fillPayload(TradeFilled e) {
        return "contract=" + e.contractSymbol()
                + ", filledAvgPrice=" + e.filledAvgPrice().toPlainString()
                + ", filledQty=" + e.filledQty()
                + ", status=" + e.status()
                + ", alpacaOrderId=" + e.alpacaOrderId();
    }

    static String orderSubmittedPayload(TradeOrderSubmitted e) {
        return "contract=" + e.contractSymbol()
                + ", quantity=" + e.quantity()
                + ", status=" + e.status()
                + ", alpacaOrderId=" + e.alpacaOrderId();
    }

    static String rejectedPayload(TradeOrderRejected e) {
        return "contract=" + e.contractSymbol() + ", httpStatus=" + e.httpStatus() + ", reason=" + e.reason();
    }

    static String stopPayload(TradeStopTriggered e) {
        return "contract=" + e.contractSymbol()
                + ", stopReference=" + e.stopReference()
                + ", barClose=" + e.barClose().toPlainString()
                + ", barTimestamp=" + e.barTimestamp();
    }

    static String trailBreachPayload(TradeTrailBreached e) {
        return "contract=" + e.contractSymbol()
                + ", nbboMid=" + e.nbboMid().toPlainString()
                + ", exitFloorPct=" + e.exitFloorPct().toPlainString();
    }

    static String eodFlattenPayload(TradeEodFlattened e) {
        return "alpacaOrderId=" + e.alpacaOrderId() + ", correlationId=" + e.correlationId();
    }
}
