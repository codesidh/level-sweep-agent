package com.levelsweep.marketdata.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.marketdata.alpaca.AlpacaConfig;
import com.levelsweep.marketdata.buffer.TickRingBuffer;
import com.levelsweep.marketdata.connection.ConnectionMonitor;
import com.levelsweep.shared.domain.marketdata.Tick;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
        LivePipeline pipeline =
                new LivePipeline(cfg, new TickRingBuffer(1000), new ConnectionMonitor("alpaca-ws", Clock.systemUTC()));

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
        LivePipeline pipeline = new LivePipeline(cfg, buffer, new ConnectionMonitor("alpaca-ws", Clock.systemUTC()));

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
