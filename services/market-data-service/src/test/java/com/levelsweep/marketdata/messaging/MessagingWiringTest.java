package com.levelsweep.marketdata.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.levelsweep.marketdata.alpaca.AlpacaConfig;
import com.levelsweep.marketdata.buffer.TickRingBuffer;
import com.levelsweep.marketdata.connection.ConnectionMonitor;
import com.levelsweep.marketdata.live.LivePipeline;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Tick;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.Record;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies that {@link MessagingWiring#onStart(StartupEvent)} registers a bar listener on
 * {@link LivePipeline} that delegates to {@link BarEmitter}, and that bars driven through
 * the live pipeline reach the emitter end-to-end.
 *
 * <p>Plain JUnit (no {@code @QuarkusTest}) — we wire a real {@link LivePipeline} with a
 * blank Alpaca config (skips WS connect, drainer still runs) and a mock {@link BarEmitter}.
 */
@ExtendWith(MockitoExtension.class)
class MessagingWiringTest {

    private static final Instant SESSION_START = Instant.parse("2026-04-30T13:30:00Z"); // 09:30 ET

    @Mock
    MutinyEmitter<Record<String, Bar>> oneMin;

    @Mock
    MutinyEmitter<Record<String, Bar>> twoMin;

    @Mock
    MutinyEmitter<Record<String, Bar>> fifteenMin;

    @Mock
    MutinyEmitter<Record<String, Bar>> daily;

    @Test
    void registersListenerOnStartupAndForwardsBarsToEmitter() throws Exception {
        when(oneMin.send(any())).thenReturn(Uni.createFrom().voidItem());
        when(twoMin.send(any())).thenReturn(Uni.createFrom().voidItem());
        when(fifteenMin.send(any())).thenReturn(Uni.createFrom().voidItem());
        when(daily.send(any())).thenReturn(Uni.createFrom().voidItem());

        BarEmitter emitter = new BarEmitter(oneMin, twoMin, fifteenMin, daily);
        AlpacaConfig cfg = new StubConfig("");
        TickRingBuffer buffer = new TickRingBuffer(10_000);
        LivePipeline pipeline = new LivePipeline(cfg, buffer, new ConnectionMonitor("alpaca-ws", Clock.systemUTC()));
        MessagingWiring wiring = new MessagingWiring(pipeline, emitter);

        pipeline.start(new StartupEvent());
        wiring.onStart(new StartupEvent());

        // Drive ticks straddling a 1-min and 2-min boundary so both ONE_MIN and TWO_MIN
        // bars close. 15-min and DAILY won't close in this window — that's fine; the
        // emitter assertion below scopes itself to the timeframes we actually closed.
        BigDecimal price = new BigDecimal("594.00");
        for (int i = 0; i < 60; i++) {
            // First minute: [13:30:00, 13:31:00)
            buffer.offer(tick("SPY", price.toPlainString(), 10, SESSION_START.plusSeconds(i)));
        }
        for (int i = 0; i < 60; i++) {
            // Second minute: [13:31:00, 13:32:00) — crossing into minute 32 closes the
            // first 2-min bar.
            buffer.offer(tick("SPY", price.toPlainString(), 10, SESSION_START.plusSeconds(60 + i)));
        }
        // Crossing into the third minute closes the second 1-min bar AND the first
        // 2-min bar [13:30:00, 13:32:00).
        buffer.offer(tick("SPY", price.toPlainString(), 10, SESSION_START.plus(Duration.ofMinutes(2))));

        // Drainer is asynchronous — wait for the sends to be observed.
        awaitUntil(
                () -> {
                    try {
                        verify(oneMin, org.mockito.Mockito.atLeastOnce()).send(any());
                        verify(twoMin, org.mockito.Mockito.atLeastOnce()).send(any());
                        return true;
                    } catch (AssertionError | RuntimeException e) {
                        return false;
                    }
                },
                3_000L);

        // Sanity: timeframes we never closed should not have been emitted.
        verify(fifteenMin, never()).send(any());
        verify(daily, never()).send(any());

        pipeline.stop(new io.quarkus.runtime.ShutdownEvent());
    }

    private static Tick tick(String symbol, String price, long size, Instant ts) {
        return new Tick(symbol, new BigDecimal(price), size, ts);
    }

    private static void awaitUntil(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(20L);
        }
        if (!cond.getAsBoolean()) {
            throw new AssertionError("condition did not become true within " + timeoutMs + " ms");
        }
    }

    /** Minimal stub of {@link AlpacaConfig} returning a blank api-key so WS connect is skipped. */
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
            return "iex";
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
            return 10_000;
        }
    }
}
