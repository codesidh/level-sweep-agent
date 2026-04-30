package com.levelsweep.marketdata.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.marketdata.alpaca.AlpacaConfig;
import com.levelsweep.marketdata.buffer.TickRingBuffer;
import com.levelsweep.marketdata.connection.ConnectionMonitor;
import com.levelsweep.marketdata.connection.ConnectionState;
import com.levelsweep.marketdata.live.LivePipeline;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit unit tests for {@link MarketDataReadinessCheck}. Stubs the FSM via
 * {@link ConnectionMonitor#recordError(Throwable)} to drive the threshold-based
 * UNHEALTHY transition without reflection.
 */
class MarketDataReadinessCheckTest {

    @Test
    void idleModeIsReady() {
        AlpacaConfig cfg = new StubAlpacaConfig("");
        ConnectionMonitor monitor = new ConnectionMonitor("alpaca-ws", Clock.systemUTC());
        LivePipeline pipeline = new LivePipeline(cfg, new TickRingBuffer(1000), monitor);

        HealthCheckResponse response = new MarketDataReadinessCheck(pipeline, cfg).call();

        assertThat(response.getName()).isEqualTo("market-data-readiness");
        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(response.getData()).isPresent();
        assertThat(response.getData().get().get("mode")).isEqualTo("idle");
        assertThat(response.getData().get().get("connectionState")).isEqualTo("HEALTHY");
    }

    @Test
    void unhealthyFsmIsDown() {
        AlpacaConfig cfg = new StubAlpacaConfig("AKxxxx");
        ConnectionMonitor monitor = new ConnectionMonitor("alpaca-ws", Clock.systemUTC());
        LivePipeline pipeline = new LivePipeline(cfg, new TickRingBuffer(1000), monitor);

        // 5 errors within the default 30s window force UNHEALTHY (circuit-breaker open).
        for (int i = 0; i < 5; i++) {
            monitor.recordError(new RuntimeException("test"));
        }
        assertThat(monitor.state())
                .as("test setup: monitor should be UNHEALTHY after 5 consecutive errors")
                .isEqualTo(ConnectionState.UNHEALTHY);

        HealthCheckResponse response = new MarketDataReadinessCheck(pipeline, cfg).call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        assertThat(response.getData()).isPresent();
        assertThat(response.getData().get().get("connectionState")).isEqualTo("UNHEALTHY");
        assertThat(response.getData().get().get("dependency")).isEqualTo("alpaca-ws");
        assertThat(response.getData().get()).containsKey("droppedCount");
    }

    @Test
    void healthyLiveIsReady() {
        AlpacaConfig cfg = new StubAlpacaConfig("AKxxxx");
        ConnectionMonitor monitor = new ConnectionMonitor("alpaca-ws", Clock.systemUTC());
        LivePipeline pipeline = new LivePipeline(cfg, new TickRingBuffer(1000), monitor);

        HealthCheckResponse response = new MarketDataReadinessCheck(pipeline, cfg).call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(response.getData()).isPresent();
        assertThat(response.getData().get().get("mode")).isEqualTo("live");
        assertThat(response.getData().get().get("connectionState")).isEqualTo("HEALTHY");
        assertThat(response.getData().get()).containsKey("wsAttached");
    }

    @Test
    void degradedFsmIsStillReady() {
        AlpacaConfig cfg = new StubAlpacaConfig("AKxxxx");
        ConnectionMonitor monitor = new ConnectionMonitor("alpaca-ws", Clock.systemUTC());
        LivePipeline pipeline = new LivePipeline(cfg, new TickRingBuffer(1000), monitor);

        // 3 errors within the window cross the DEGRADED threshold; UNHEALTHY needs 5.
        for (int i = 0; i < 3; i++) {
            monitor.recordError(new RuntimeException("test"));
        }
        assertThat(monitor.state())
                .as("test setup: monitor should be DEGRADED after 3 errors")
                .isEqualTo(ConnectionState.DEGRADED);

        HealthCheckResponse response = new MarketDataReadinessCheck(pipeline, cfg).call();

        assertThat(response.getStatus())
                .as("DEGRADED is operational — readiness must stay UP")
                .isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(response.getData()).isPresent();
        assertThat(response.getData().get().get("connectionState")).isEqualTo("DEGRADED");
    }

    /** Minimal {@link AlpacaConfig} stub. */
    private static final class StubAlpacaConfig implements AlpacaConfig {
        private final String apiKey;

        StubAlpacaConfig(String apiKey) {
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
