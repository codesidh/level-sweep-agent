package com.levelsweep.marketdata.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.marketdata.alpaca.AlpacaConfig;
import com.levelsweep.marketdata.connection.ConnectionState;
import com.levelsweep.marketdata.live.LivePipeline;
import java.time.Duration;
import java.util.List;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit unit tests for {@link MarketDataReadinessCheck}. Drives the FSM
 * by recording errors against the {@code ConnectionMonitor} owned by the pipeline
 * (exposed via the public {@code connectionMonitor()} getter) — no reflection
 * required.
 */
class MarketDataReadinessCheckTest {

    @Test
    void idleModeIsReady() {
        AlpacaConfig cfg = new StubAlpacaConfig("");
        // Use the public single-arg constructor — the package-private 3-arg test seam
        // is only accessible from inside `marketdata.live`.
        LivePipeline pipeline = new LivePipeline(cfg);

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
        LivePipeline pipeline = new LivePipeline(cfg);

        // 5 errors within the default 30s window force UNHEALTHY (circuit-breaker open).
        for (int i = 0; i < 5; i++) {
            pipeline.connectionMonitor().recordError(new RuntimeException("test"));
        }
        assertThat(pipeline.connectionMonitor().state())
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
        LivePipeline pipeline = new LivePipeline(cfg);

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
        LivePipeline pipeline = new LivePipeline(cfg);

        // 3 errors within the window cross the DEGRADED threshold; UNHEALTHY needs 5.
        for (int i = 0; i < 3; i++) {
            pipeline.connectionMonitor().recordError(new RuntimeException("test"));
        }
        assertThat(pipeline.connectionMonitor().state())
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
