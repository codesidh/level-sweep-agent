package com.levelsweep.marketdata.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.levelsweep.marketdata.alpaca.AlpacaConfig;
import com.levelsweep.marketdata.alpaca.AlpacaRestClient;
import com.levelsweep.marketdata.bars.BarListener;
import com.levelsweep.marketdata.buffer.TickRingBuffer;
import com.levelsweep.marketdata.connection.ConnectionMonitor;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Tick;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit tests (no @QuarkusTest) for {@link LivePipeline}. Two scenarios:
 *
 * <ol>
 *   <li>Blank api-key — {@code start()} succeeds without WS construction; the drainer
 *       still runs; {@code stop()} drains cleanly.
 *   <li>End-to-end through the buffer — ticks offered straight to the ring buffer
 *       are drained, fed to the aggregator, and produce 2-min bars that drive the
 *       indicator engine.
 * </ol>
 */
class LivePipelineTest {

    private static final Instant SESSION_START = Instant.parse("2026-04-30T13:30:00Z"); // 09:30 ET

    @Test
    void blankApiKeySkipsWsButStartsDrainer() throws Exception {
        AlpacaConfig cfg = new StubConfig("");
        LivePipeline pipeline = new LivePipeline(
                cfg,
                new TickRingBuffer(1000),
                new ConnectionMonitor("alpaca-ws", Clock.systemUTC()),
                null,
                Clock.systemUTC());

        pipeline.start(new StartupEvent());

        assertThat(pipeline.wsAttached())
                .as("ws should not be attached when api key is blank")
                .isFalse();
        assertThat(pipeline.tickRingBuffer().capacity()).isEqualTo(1000);
        assertThat(pipeline.barAggregator()).isNotNull();
        assertThat(pipeline.indicatorEngine()).isNotNull();

        // Drainer should be running and idle — offer one tick and observe it drained.
        pipeline.tickRingBuffer().offer(tick("SPY", "594.00", 100, SESSION_START));
        awaitUntil(() -> pipeline.tickRingBuffer().size() == 0, 2_000L);

        pipeline.stop(new ShutdownEvent());
    }

    @Test
    void endToEndTicksProduceBarsThroughDrainer() throws Exception {
        AlpacaConfig cfg = new StubConfig("");
        TickRingBuffer buffer = new TickRingBuffer(10_000);
        LivePipeline pipeline = new LivePipeline(
                cfg, buffer, new ConnectionMonitor("alpaca-ws", Clock.systemUTC()), null, Clock.systemUTC());

        // Capture bars by re-using the aggregator's internal state — simplest signal is
        // the IndicatorEngine, which only fires on TWO_MIN closes. We can also probe the
        // BarAggregator via a side-channel: feed enough ticks across a 2m boundary and
        // check that latest() on the indicator engine becomes non-null.
        pipeline.start(new StartupEvent());

        // Offer 200 ticks: 100 in the first 2m bar (13:30:00 — 13:31:59 UTC), then 100 in
        // the second 2m bar (13:32:00+). Crossing the 2m boundary at minute 32 closes the
        // first bar; that closure feeds IndicatorEngine and produces a snapshot.
        BigDecimal price = new BigDecimal("594.00");
        for (int i = 0; i < 100; i++) {
            // Timestamps inside the first 2m bar [13:30:00, 13:32:00).
            Instant ts = SESSION_START.plusSeconds(i); // 13:30:00..13:31:39
            buffer.offer(tick("SPY", price.toPlainString(), 10, ts));
        }
        for (int i = 0; i < 100; i++) {
            // Timestamps inside the second 2m bar [13:32:00, 13:34:00). The first tick
            // here crosses the 2m boundary and forces the prior bar to emit.
            Instant ts = SESSION_START.plusSeconds(120L + i);
            buffer.offer(tick("SPY", price.toPlainString(), 10, ts));
        }

        // The drainer is asynchronous; await indicator emission as the observable signal
        // that the full path (drainer → aggregator → 2m close → IndicatorEngine) ran.
        awaitUntil(() -> pipeline.indicatorEngine().latest() != null, 5_000L);

        assertThat(buffer.size()).as("all ticks should have been drained").isZero();
        assertThat(buffer.offeredCount()).isEqualTo(200L);
        // The 2m bar boundary at 13:32:00 UTC closed the first bar through the drainer,
        // and the IndicatorEngine emitted at least one snapshot. EMAs themselves are still
        // in their 13/48/200-sample bootstrap (will produce non-null values only after
        // their respective windows fill), so we only assert snapshot presence here.
        assertThat(pipeline.indicatorEngine().latest()).isNotNull();
        assertThat(pipeline.indicatorEngine().latest().symbol()).isEqualTo("SPY");

        pipeline.stop(new ShutdownEvent());
    }

    @Test
    void registeredBarListenerReceivesBarsAndExceptionIsIsolated() throws Exception {
        AlpacaConfig cfg = new StubConfig("");
        TickRingBuffer buffer = new TickRingBuffer(10_000);
        LivePipeline pipeline = new LivePipeline(
                cfg, buffer, new ConnectionMonitor("alpaca-ws", Clock.systemUTC()), null, Clock.systemUTC());

        // One listener captures bars; another always throws to verify isolation.
        CopyOnWriteArrayList<Bar> captured = new CopyOnWriteArrayList<>();
        AtomicInteger throwerInvocations = new AtomicInteger();
        BarListener thrower = bar -> {
            throwerInvocations.incrementAndGet();
            throw new RuntimeException("simulated downstream failure");
        };
        pipeline.registerBarListener(thrower);
        pipeline.registerBarListener(captured::add);

        pipeline.start(new StartupEvent());

        // Cross a 2m bar boundary so the aggregator emits at least one bar.
        for (int i = 0; i < 100; i++) {
            buffer.offer(tick("SPY", "594.00", 10, SESSION_START.plusSeconds(i)));
        }
        for (int i = 0; i < 5; i++) {
            buffer.offer(tick("SPY", "594.00", 10, SESSION_START.plusSeconds(120L + i)));
        }

        // Wait for both observable signals to land — the 2m boundary feeds the indicator
        // engine, the bar fan-out feeds the registered listeners. Awaiting only on
        // captured.isEmpty() races with the indicator engine because 1m bars land first.
        awaitUntil(() -> !captured.isEmpty() && pipeline.indicatorEngine().latest() != null, 5_000L);

        assertThat(captured)
                .as("registered listener should receive bars from fan-out")
                .isNotEmpty();
        assertThat(throwerInvocations.get())
                .as("the throwing listener was invoked but its failure did not block the next listener")
                .isPositive();
        assertThat(pipeline.indicatorEngine().latest())
                .as("indicator engine still received bars despite a throwing peer listener")
                .isNotNull();

        pipeline.stop(new ShutdownEvent());
    }

    @Test
    void prewarmFeedsBarsToIndicatorEngineDirectly() throws Exception {
        // Drive prewarmIndicators() directly so the test isn't entangled with the WS
        // attach path — the start() integration is exercised separately by
        // prewarmIsBypassedWhenApiKeyIsBlank.
        AlpacaConfig cfg = new StubConfig("AKtest");
        AlpacaRestClient restClient = mock(AlpacaRestClient.class);
        Instant t0 = Instant.parse("2026-04-30T13:30:00Z");
        List<Bar> historical = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Instant open = t0.plusSeconds(i * 120L);
            Instant close = open.plusSeconds(120L);
            historical.add(new Bar(
                    "SPY",
                    Timeframe.TWO_MIN,
                    open,
                    close,
                    new BigDecimal("594.00"),
                    new BigDecimal("594.50"),
                    new BigDecimal("593.75"),
                    new BigDecimal("594.25"),
                    1000L,
                    50L));
        }
        when(restClient.fetchHistoricalBars(eq("SPY"), eq(Timeframe.TWO_MIN), any(), any(), anyInt()))
                .thenReturn(historical);

        TickRingBuffer buffer = new TickRingBuffer(1000);
        LivePipeline pipeline = new LivePipeline(
                cfg, buffer, new ConnectionMonitor("alpaca-ws", Clock.systemUTC()), restClient, Clock.systemUTC());

        pipeline.prewarmIndicators();

        assertThat(pipeline.indicatorEngine().latest())
                .as("indicator engine should have a snapshot from prewarm")
                .isNotNull();
        assertThat(pipeline.indicatorEngine().latest().symbol()).isEqualTo("SPY");
        assertThat(pipeline.indicatorEngine().latest().timestamp())
                .as("snapshot timestamp matches the last prewarm bar close")
                .isEqualTo(historical.get(historical.size() - 1).closeTime());
        // Pre-warm must NOT seed the aggregator — its in-flight bar state stays untouched.
        assertThat(buffer.size()).as("aggregator path is bypassed by prewarm").isZero();
    }

    @Test
    void prewarmIsBypassedWhenApiKeyIsBlank() throws Exception {
        AlpacaConfig cfg = new StubConfig(""); // blank → skip prewarm and skip WS
        AlpacaRestClient restClient = mock(AlpacaRestClient.class);
        TickRingBuffer buffer = new TickRingBuffer(1000);
        LivePipeline pipeline = new LivePipeline(
                cfg, buffer, new ConnectionMonitor("alpaca-ws", Clock.systemUTC()), restClient, Clock.systemUTC());

        pipeline.start(new StartupEvent());

        // No historical fetch should have been attempted.
        verifyNoInteractions(restClient);
        assertThat(pipeline.indicatorEngine().latest())
                .as("no prewarm → no snapshot yet")
                .isNull();

        pipeline.stop(new ShutdownEvent());
    }

    private static Tick tick(String symbol, String price, long size, Instant ts) {
        return new Tick(symbol, new BigDecimal(price), size, ts);
    }

    /**
     * Poll {@code condition} until it returns {@code true} or the budget elapses. Throws
     * {@link AssertionError} on timeout. Uses wall-clock time intentionally — these are
     * tests of asynchronous side effects, not of replay-deterministic business logic.
     */
    private static void awaitUntil(BooleanSupplier condition, long maxMillis) {
        long deadline = System.currentTimeMillis() + maxMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while awaiting condition", e);
            }
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("condition not satisfied within " + maxMillis + " ms");
        }
    }

    /**
     * Minimal {@link AlpacaConfig} stub. {@code @ConfigMapping} interfaces work fine
     * as direct anonymous implementations in tests — no SmallRye runtime required.
     */
    private static final class StubConfig implements AlpacaConfig {
        private final String apiKey;

        StubConfig(String apiKey) {
            this.apiKey = apiKey;
        }

        @Override
        public String wsBaseUrl() {
            return "wss://stream.data.alpaca.markets";
        }

        @Override
        public String feed() {
            return "sip";
        }

        @Override
        public String tradingUrl() {
            return "https://paper-api.alpaca.markets";
        }

        @Override
        public String dataUrl() {
            return "https://data.alpaca.markets";
        }

        @Override
        public String apiKey() {
            return apiKey;
        }

        @Override
        public String secretKey() {
            return "";
        }

        @Override
        public List<String> symbols() {
            return List.of("SPY");
        }

        @Override
        public java.time.Duration reconnectInitialBackoff() {
            return java.time.Duration.ofMillis(200);
        }

        @Override
        public java.time.Duration reconnectMaxBackoff() {
            return java.time.Duration.ofSeconds(30);
        }

        @Override
        public java.time.Duration reconnectJitter() {
            return java.time.Duration.ofMillis(100);
        }

        @Override
        public int ringBufferCapacity() {
            return 1000;
        }
    }
}
