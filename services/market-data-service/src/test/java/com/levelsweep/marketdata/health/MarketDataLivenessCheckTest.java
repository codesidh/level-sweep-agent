package com.levelsweep.marketdata.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.marketdata.alpaca.AlpacaConfig;
import com.levelsweep.marketdata.buffer.TickRingBuffer;
import com.levelsweep.marketdata.connection.ConnectionMonitor;
import com.levelsweep.marketdata.live.LivePipeline;
import com.levelsweep.shared.domain.marketdata.Tick;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit unit tests for {@link MarketDataLivenessCheck}. Avoids
 * {@code @QuarkusTest} so the suite stays cheap on CI.
 */
class MarketDataLivenessCheckTest {

    @Test
    void livenessAlwaysUpWhenPipelineConstructed() {
        AlpacaConfig cfg = new StubAlpacaConfig("");
        TickRingBuffer buffer = new TickRingBuffer(1000);
        LivePipeline pipeline = new LivePipeline(cfg, buffer, new ConnectionMonitor("alpaca-ws", Clock.systemUTC()));

        // Push a tick so offeredCount is observably non-zero in the response data.
        buffer.offer(new Tick("SPY", new BigDecimal("594.00"), 100L, Instant.parse("2026-04-30T13:30:00Z")));

        MarketDataLivenessCheck check = new MarketDataLivenessCheck(pipeline);
        HealthCheckResponse response = check.call();

        assertThat(response.getName()).isEqualTo("market-data-pipeline");
        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(response.getData()).isPresent();
        assertThat(response.getData().get()).containsKey("ringBufferSize").containsKey("offeredCount");
        assertThat(response.getData().get().get("offeredCount")).isEqualTo(1L);
    }

    /** Minimal {@link AlpacaConfig} stub — same shape as {@code LivePipelineTest.StubConfig}. */
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
