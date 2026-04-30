package com.levelsweep.marketdata.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.marketdata.alpaca.AlpacaConfig;
import com.levelsweep.marketdata.connection.ConnectionMonitor;
import com.levelsweep.marketdata.connection.ConnectionState;
import com.levelsweep.marketdata.live.LivePipeline;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Tick;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.runtime.StartupEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit tests for {@link MetricsBinding}. Verifies that the binding
 * registers the meters the Phase 1 alerts depend on (connection.state,
 * tick.buffer.size, tick.dropped.total, bar.emitted.total, bar.emit.duration)
 * and that they reflect live pipeline state.
 */
class MetricsBindingTest {

    private static final Instant T0 = Instant.parse("2026-04-30T13:30:00Z");

    private MeterRegistry registry;
    private LivePipeline pipeline;
    private MetricsBinding binding;
    private ConnectionMonitor monitor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        AlpacaConfig cfg = new StubConfig();
        // Use the public single-arg constructor — it instantiates its own
        // TickRingBuffer + ConnectionMonitor with the same defaults the CDI
        // path uses. We borrow the monitor reference via the accessor so we
        // can drive the FSM directly.
        pipeline = new LivePipeline(cfg);
        monitor = pipeline.connectionMonitor();
        binding = new MetricsBinding(registry, pipeline);
    }

    @Test
    void registersAllPhase1Meters() {
        binding.onStart(new StartupEvent());

        assertThat(registry.find("connection.state")
                        .tag("dependency", MetricsBinding.DEPENDENCY_ALPACA_WS)
                        .gauge())
                .as("connection.state gauge tagged dependency=alpaca-ws")
                .isNotNull();
        assertThat(registry.find("tick.buffer.size").gauge()).isNotNull();
        assertThat(registry.find("tick.buffer.capacity").gauge()).isNotNull();
        assertThat(registry.find("tick.dropped.total").functionCounter()).isNotNull();
        assertThat(registry.find("tick.offered.total").functionCounter()).isNotNull();
        assertThat(registry.find("quote.received.total").functionCounter()).isNotNull();
        assertThat(registry.find("bar.emitted.total").counter()).isNotNull();
        assertThat(registry.find("bar.emit.duration").timer()).isNotNull();
    }

    @Test
    void connectionStateGaugeReflectsFsmOrdinal() {
        binding.onStart(new StartupEvent());

        assertThat(registry.find("connection.state").gauge().value())
                .as("starts at HEALTHY=0")
                .isEqualTo(0.0d);

        // Drive the FSM to UNHEALTHY (5 errors within window).
        for (int i = 0; i < 5; i++) {
            monitor.recordError(new RuntimeException("boom-" + i));
        }
        assertThat(monitor.state()).isEqualTo(ConnectionState.UNHEALTHY);
        assertThat(registry.find("connection.state").gauge().value())
                .as("UNHEALTHY ordinal == 2")
                .isEqualTo(2.0d);
    }

    @Test
    void tickBufferGaugesTrackUnderlyingState() {
        binding.onStart(new StartupEvent());

        assertThat(registry.find("tick.buffer.size").gauge().value()).isEqualTo(0.0d);
        assertThat(registry.find("tick.buffer.capacity").gauge().value()).isEqualTo(1000.0d);

        pipeline.tickRingBuffer().offer(tick("SPY", "500.00", 100L, T0));
        pipeline.tickRingBuffer().offer(tick("SPY", "500.10", 100L, T0.plusSeconds(1)));

        assertThat(registry.find("tick.buffer.size").gauge().value())
                .as("size reflects underlying buffer")
                .isEqualTo(2.0d);
        assertThat(registry.find("tick.offered.total").functionCounter().count())
                .isEqualTo(2.0d);
    }

    @Test
    void barEmittedCounterIncrementsOnRegisteredListener() {
        binding.onStart(new StartupEvent());

        // The binding's onBar is registered as a fan-out listener via
        // pipeline.registerBarListener(...). We can drive it directly by
        // invoking the BarListener fan-out — easiest path is to fabricate a
        // bar and call the binding's listener method.
        binding.onBar(bar(T0));
        binding.onBar(bar(T0.plus(Duration.ofMinutes(2))));

        assertThat(registry.find("bar.emitted.total").counter().count())
                .as("counter increments per bar")
                .isEqualTo(2.0d);
        assertThat(registry.find("bar.emit.duration").timer().count())
                .as("timer records per bar")
                .isEqualTo(2L);
    }

    private static Tick tick(String symbol, String price, long size, Instant ts) {
        return new Tick(symbol, new BigDecimal(price), size, ts);
    }

    private static Bar bar(Instant openTime) {
        return new Bar(
                "SPY",
                Timeframe.TWO_MIN,
                openTime,
                openTime.plus(Duration.ofMinutes(2)),
                new BigDecimal("500.00"),
                new BigDecimal("500.20"),
                new BigDecimal("499.90"),
                new BigDecimal("500.10"),
                10_000L,
                42L);
    }

    private static final class StubConfig implements AlpacaConfig {
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
            return "";
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
        public Duration reconnectInitialBackoff() {
            return Duration.ofMillis(200);
        }

        @Override
        public Duration reconnectMaxBackoff() {
            return Duration.ofSeconds(30);
        }

        @Override
        public Duration reconnectJitter() {
            return Duration.ofMillis(100);
        }

        @Override
        public int ringBufferCapacity() {
            return 1000;
        }
    }
}
