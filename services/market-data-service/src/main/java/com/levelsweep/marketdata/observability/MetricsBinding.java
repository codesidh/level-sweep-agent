package com.levelsweep.marketdata.observability;

import com.levelsweep.marketdata.live.LivePipeline;
import com.levelsweep.shared.domain.marketdata.Bar;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Micrometer instrumentation for the live pipeline. Translates internal
 * pipeline state (Connection FSM, ring buffer levels, bar emit cadence) into
 * Micrometer meters so the App Insights Java agent (attached as -javaagent in
 * Dockerfile.jvm) can ship them as customMetrics rows. The five Phase 1 alerts
 * defined in {@code infra/modules/observability/alerts.tf} key off these
 * meter names.
 *
 * <p>Naming follows Micrometer's snake-case convention with a {@code
 * dependency} tag where applicable. App Insights translates dots to
 * underscores in customMetrics names, so the alert KQL queries match
 * {@code connection_state}, {@code tick_buffer_size}, etc.
 *
 * <p>Each meter is registered exactly once at {@link StartupEvent} (idempotent
 * — Micrometer dedupes on identity tags). Counters/timers that need updates
 * per-bar are exposed as fields and incremented from a registered
 * {@link com.levelsweep.marketdata.bars.BarListener}.
 */
@ApplicationScoped
public class MetricsBinding {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsBinding.class);

    /** Tag value used on connection_state and related meters. */
    static final String DEPENDENCY_ALPACA_WS = "alpaca-ws";

    private final MeterRegistry registry;
    private final LivePipeline pipeline;

    private volatile Counter barEmittedTotal;
    private volatile Timer barEmitDuration;

    @Inject
    public MetricsBinding(MeterRegistry registry, LivePipeline pipeline) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }

    void onStart(@Observes StartupEvent ev) {
        LOG.info("registering market-data Micrometer meters (connection.state, tick.buffer.*, bar.emitted.*)");
        register();
    }

    /**
     * Package-private so unit tests can drive registration without a CDI
     * container.
     */
    void register() {
        // ConnectionState ordinal — HEALTHY=0, DEGRADED=1, UNHEALTHY=2, RECOVERING=3.
        // Phase 1 alert "Alpaca WS CB open" fires when this == 2 sustained for 1 minute.
        Gauge.builder("connection.state", pipeline, p -> p.connectionMonitor()
                        .state()
                        .ordinal())
                .description("Connection FSM ordinal — 0=HEALTHY, 1=DEGRADED, 2=UNHEALTHY, 3=RECOVERING")
                .tags(Tags.of("dependency", DEPENDENCY_ALPACA_WS))
                .register(registry);

        // Live ring-buffer depth — drainer health proxy. No alert wired in Phase 1
        // but useful in the runbook + for triaging the "drop rate" alert.
        Gauge.builder("tick.buffer.size", pipeline, p -> p.tickRingBuffer().size())
                .description("Current depth of the in-memory tick ring buffer")
                .register(registry);

        Gauge.builder("tick.buffer.capacity", pipeline, p -> p.tickRingBuffer().capacity())
                .description("Configured capacity of the in-memory tick ring buffer")
                .register(registry);

        // Drop counter — cumulative; the App Insights alert query computes a
        // rate over the bin window. FunctionCounter is the right tool because
        // the underlying counter (TickRingBuffer#droppedCount) is a long the
        // buffer manages itself.
        FunctionCounter.builder("tick.dropped.total", pipeline, p ->
                        (double) p.tickRingBuffer().droppedCount())
                .description("Total ticks dropped by the ring buffer (drop-oldest on overflow)")
                .register(registry);

        FunctionCounter.builder("tick.offered.total", pipeline, p ->
                        (double) p.tickRingBuffer().offeredCount())
                .description("Total ticks offered to the ring buffer")
                .register(registry);

        // Quote count — ditto.
        FunctionCounter.builder("quote.received.total", pipeline, p -> (double) p.quoteCount())
                .description("Total quotes received from the live WS feed")
                .register(registry);

        // Bar fan-out instrumentation. We register a BarListener that
        // increments a counter and records a Timer per emit. The Timer is the
        // P99 source for the "Hot-path bar emit P99 > 500ms" alert.
        this.barEmittedTotal = Counter.builder("bar.emitted.total")
                .description("Total bars emitted by the BarAggregator across all timeframes")
                .register(registry);
        this.barEmitDuration = Timer.builder("bar.emit.duration")
                .description("Wall-clock duration of the BarListener fan-out callback")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        pipeline.registerBarListener(this::onBar);
    }

    /**
     * Bar listener — counts emits and records the wall-clock arrival cadence.
     * The bar's own {@code timestamp()} field is the bar-close instant; we
     * sample {@code System.nanoTime()} on entry and use that as the timer
     * sample. The listener body itself is intentionally tiny so the recorded
     * duration approximates "time the binding spends per bar" rather than
     * "time the aggregator spends emitting" — Phase 2 will replace this with
     * a proper end-to-end span when the Decision Engine is wired in.
     *
     * <p>For Phase 1 the {@link Counter#count()} is the load-bearing signal
     * (drives the "bars stalled" alert); the Timer is provisioned so the
     * "P99 emit latency" alert has a real meter to bind to without further
     * code changes.
     */
    void onBar(Bar bar) {
        long t0 = System.nanoTime();
        try {
            barEmittedTotal.increment();
        } finally {
            barEmitDuration.record(java.time.Duration.ofNanos(System.nanoTime() - t0));
        }
    }
}
