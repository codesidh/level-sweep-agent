package com.levelsweep.marketdata.messaging;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.levelsweep.marketdata.alpaca.AlpacaConfig;
import com.levelsweep.marketdata.live.LivePipeline;
import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Tick;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.Record;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies that {@link MessagingWiring#onStart(StartupEvent)} registers a bar listener on
 * {@link LivePipeline} that delegates to {@link BarEmitter}. Bars are fed directly into the
 * pipeline's {@code BarAggregator} (the public test seam), bypassing the drainer thread
 * and the start/stop lifecycle entirely — neither is exercised by this PR.
 *
 * <p>Plain JUnit (no {@code @QuarkusTest}); CDI is not bootstrapped — we wire dependencies
 * by hand. The {@code MutinyEmitter} fields are Mockito mocks so no Kafka broker is needed.
 *
 * <p>{@code MutinyEmitter#send} has two overloads ({@code send(T)} and
 * {@code <M extends Message> send(M)}); we disambiguate via
 * {@code ArgumentMatchers.<Record<String, Bar>>any()}.
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

    @Mock
    MutinyEmitter<Record<String, IndicatorSnapshot>> indicators2m;

    @SuppressWarnings("unchecked")
    private static Record<String, Bar> anyRecord() {
        return ArgumentMatchers.<Record<String, Bar>>any();
    }

    @Test
    void onStartRegistersListenerThatForwardsBarsToEmitter() {
        when(oneMin.send(anyRecord())).thenReturn(Uni.createFrom().voidItem());
        when(twoMin.send(anyRecord())).thenReturn(Uni.createFrom().voidItem());

        BarEmitter emitter = new BarEmitter(oneMin, twoMin, fifteenMin, daily);
        IndicatorSnapshotEmitter snapshotEmitter = new IndicatorSnapshotEmitter(indicators2m);
        LivePipeline pipeline = new LivePipeline(new StubConfig());
        MessagingWiring wiring = new MessagingWiring(pipeline, emitter, snapshotEmitter);

        // Register the messaging listener on the pipeline.
        wiring.onStart(new StartupEvent());

        // Drive ticks straight into the aggregator (bypasses ring buffer + drainer; the
        // bar fan-out lambda runs on this thread, so the assertion below is synchronous).
        BigDecimal price = new BigDecimal("594.00");
        for (int i = 0; i < 60; i++) {
            // First minute: [13:30:00, 13:31:00)
            pipeline.barAggregator().onTick(tick("SPY", price, SESSION_START.plusSeconds(i)));
        }
        for (int i = 0; i < 60; i++) {
            // Second minute: [13:31:00, 13:32:00)
            pipeline.barAggregator().onTick(tick("SPY", price, SESSION_START.plusSeconds(60 + i)));
        }
        // Crossing into the third minute closes the second 1-minute bar AND the first
        // 2-minute bar [13:30:00, 13:32:00) — both should reach the emitter.
        pipeline.barAggregator().onTick(tick("SPY", price, SESSION_START.plus(Duration.ofMinutes(2))));

        verify(oneMin, Mockito.atLeastOnce()).send(anyRecord());
        verify(twoMin, Mockito.atLeastOnce()).send(anyRecord());

        // We never crossed a 15-minute or daily boundary, so those channels stay quiet.
        verify(fifteenMin, never()).send(anyRecord());
        verify(daily, never()).send(anyRecord());
    }

    private static Tick tick(String symbol, BigDecimal price, Instant ts) {
        return new Tick(symbol, price, 10L, ts);
    }

    /**
     * Minimal {@link AlpacaConfig} stub. Blank api-key keeps the WS path off, and the
     * defaults match the production interface so {@link LivePipeline}'s public constructor
     * can build its own buffer + connection monitor without us touching package-private
     * test seams.
     */
    private static final class StubConfig implements AlpacaConfig {
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
            return 10_000;
        }
    }
}
