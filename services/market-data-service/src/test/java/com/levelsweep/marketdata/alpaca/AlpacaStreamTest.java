package com.levelsweep.marketdata.alpaca;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.marketdata.api.TickListener;
import com.levelsweep.marketdata.api.WsTransport;
import com.levelsweep.marketdata.buffer.TickRingBuffer;
import com.levelsweep.marketdata.connection.ConnectionMonitor;
import com.levelsweep.marketdata.connection.ConnectionState;
import com.levelsweep.marketdata.testsupport.TestClock;
import com.levelsweep.shared.domain.marketdata.Quote;
import com.levelsweep.shared.domain.marketdata.Tick;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AlpacaStreamTest {

    private StubTransport transport;
    private TickRingBuffer buffer;
    private ConnectionMonitor monitor;
    private RecordingListener listener;
    private AlpacaStream stream;

    @BeforeEach
    void setUp() {
        transport = new StubTransport();
        buffer = new TickRingBuffer(1000);
        TestClock clock = new TestClock(Instant.parse("2026-04-30T13:30:00Z"));
        monitor = new ConnectionMonitor("alpaca", clock);
        listener = new RecordingListener();
        stream = AlpacaStream.builder()
                .transport(transport)
                .decoder(new AlpacaMessageDecoder(new ObjectMapper()))
                .monitor(monitor)
                .buffer(buffer)
                .listener(listener)
                .symbols(List.of("SPY"))
                .apiKey("test-key")
                .secretKey("test-secret")
                .build();
        transport.setListener(stream.createTransportListener());
    }

    @Test
    void onOpenSendsAuthFrame() {
        AlpacaStream.await(stream.start());
        assertThat(transport.opened).isTrue();
        // Auth fires from onOpen; subscribe waits for auth_success status
        assertThat(transport.sentFrames).hasSize(1);
        assertThat(transport.sentFrames.get(0)).contains("\"action\":\"auth\"");
        assertThat(transport.sentFrames.get(0)).contains("\"key\":\"test-key\"");
        assertThat(transport.sentFrames.get(0)).contains("\"secret\":\"test-secret\"");
    }

    @Test
    void subscribeSentAfterAuthSuccess() {
        AlpacaStream.await(stream.start());
        // Simulate Alpaca's success: connected then success: authenticated
        transport.deliver("[{\"T\":\"success\",\"msg\":\"connected\"}]");
        transport.deliver("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");
        // Now subscribe should have been sent
        assertThat(transport.sentFrames).hasSize(2);
        assertThat(transport.sentFrames.get(1)).contains("\"action\":\"subscribe\"");
        assertThat(transport.sentFrames.get(1)).contains("\"trades\":[\"SPY\"]");
        assertThat(transport.sentFrames.get(1)).contains("\"quotes\":[\"SPY\"]");
        assertThat(transport.sentFrames.get(1)).contains("\"bars\":[\"SPY\"]");
    }

    @Test
    void subscribeNotSentBeforeAuthSuccess() {
        AlpacaStream.await(stream.start());
        // Some random non-success status arrives — should NOT trigger subscribe
        transport.deliver("[{\"T\":\"success\",\"msg\":\"connected\"}]");
        assertThat(transport.sentFrames).hasSize(1); // only auth
    }

    @Test
    void subscribeSentAtMostOnce() {
        AlpacaStream.await(stream.start());
        transport.deliver("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");
        // Re-deliver the same authenticated status — subscribe should not double-fire
        transport.deliver("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");
        long subFrames = transport.sentFrames.stream()
                .filter(f -> f.contains("\"action\":\"subscribe\""))
                .count();
        assertThat(subFrames).isEqualTo(1L);
    }

    @Test
    void incomingTradeFrameLandsInBufferAndListener() {
        AlpacaStream.await(stream.start());
        transport.deliver("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");
        transport.deliver("[{\"T\":\"t\",\"S\":\"SPY\",\"p\":594.23,\"s\":100,\"t\":\"2026-04-30T13:30:00Z\"}]");
        assertThat(listener.ticks).hasSize(1);
        assertThat(buffer.size()).isEqualTo(1);
    }

    @Test
    void incomingQuoteDoesNotPopulateBuffer() {
        AlpacaStream.await(stream.start());
        transport.deliver("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");
        transport.deliver(
                "[{\"T\":\"q\",\"S\":\"SPY\",\"bp\":594.20,\"bs\":100,\"ap\":594.25,\"as\":200,\"t\":\"2026-04-30T13:30:00Z\"}]");
        assertThat(listener.quotes).hasSize(1);
        assertThat(buffer.size()).isZero();
    }

    @Test
    void onOpenMarksMonitorHealthy() {
        // Simulate degraded state then re-open
        for (int i = 0; i < 3; i++) {
            transport.failWith(new RuntimeException("transient " + i));
        }
        assertThat(monitor.state()).isEqualTo(ConnectionState.DEGRADED);
        transport.fireOpen();
        assertThat(monitor.state()).isEqualTo(ConnectionState.HEALTHY);
    }

    @Test
    void transportErrorRecordsInMonitor() {
        AlpacaStream.await(stream.start());
        for (int i = 0; i < 5; i++) {
            transport.failWith(new RuntimeException("e" + i));
        }
        assertThat(monitor.state()).isEqualTo(ConnectionState.UNHEALTHY);
    }

    @Test
    void errorStatusIsTreatedAsConnectionError() {
        AlpacaStream.await(stream.start());
        for (int i = 0; i < 5; i++) {
            transport.deliver("[{\"T\":\"error\",\"code\":401,\"msg\":\"unauthorized " + i + "\"}]");
        }
        assertThat(monitor.state()).isEqualTo(ConnectionState.UNHEALTHY);
    }

    @Test
    void downstreamListenerExceptionDoesNotBreakPipeline() {
        TickListener exploding = new TickListener() {
            @Override
            public void onTick(Tick tick) {
                throw new RuntimeException("kaboom");
            }

            @Override
            public void onQuote(Quote quote) {
                // no-op
            }
        };
        AlpacaStream s2 = AlpacaStream.builder()
                .transport(transport)
                .decoder(new AlpacaMessageDecoder(new ObjectMapper()))
                .monitor(monitor)
                .buffer(buffer)
                .listener(exploding)
                .symbols(List.of("SPY"))
                .apiKey("test-key")
                .secretKey("test-secret")
                .build();
        transport.setListener(s2.createTransportListener());
        AlpacaStream.await(s2.start());
        transport.deliver("[{\"T\":\"t\",\"S\":\"SPY\",\"p\":594.23,\"s\":100,\"t\":\"2026-04-30T13:30:00Z\"}]");
        // Buffer still received it
        assertThat(buffer.size()).isEqualTo(1);
    }

    @Test
    void stopClosesTransport() {
        AlpacaStream.await(stream.start());
        AlpacaStream.await(stream.stop());
        assertThat(transport.closed).isTrue();
    }

    @Test
    void buildsWithDefaultDecoder() {
        AlpacaStream s = AlpacaStream.builder()
                .transport(new StubTransport())
                .monitor(new ConnectionMonitor("alpaca", Clock.systemUTC()))
                .buffer(new TickRingBuffer(10))
                .listener(AlpacaStream.noopListener())
                .symbols(List.of("SPY"))
                .apiKey("k")
                .secretKey("s")
                .build();
        assertThat(s).isNotNull();
        assertThat(s.connectionState()).isEqualTo(ConnectionState.HEALTHY);
        assertThat(s.monitor().dependency()).isEqualTo("alpaca");
        assertThat(AlpacaStream.defaultConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    /** In-process test transport — no real WebSocket. */
    static final class StubTransport implements WsTransport {
        boolean opened;
        boolean closed;
        final List<String> sentFrames = new ArrayList<>();
        private final AtomicReference<Listener> listener = new AtomicReference<>();

        void setListener(Listener l) {
            this.listener.set(l);
        }

        void deliver(String frame) {
            Listener l = listener.get();
            if (l != null) {
                l.onText(frame);
            }
        }

        void failWith(Throwable cause) {
            Listener l = listener.get();
            if (l != null) {
                l.onError(cause);
            }
        }

        void fireOpen() {
            Listener l = listener.get();
            if (l != null) {
                l.onOpen();
            }
        }

        @Override
        public CompletionStage<Void> connect() {
            opened = true;
            Listener l = listener.get();
            if (l != null) {
                l.onOpen();
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> send(String frame) {
            sentFrames.add(frame);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> close() {
            closed = true;
            return CompletableFuture.completedFuture(null);
        }
    }

    static final class RecordingListener implements TickListener {
        final List<Tick> ticks = new ArrayList<>();
        final List<Quote> quotes = new ArrayList<>();

        @Override
        public void onTick(Tick tick) {
            ticks.add(tick);
        }

        @Override
        public void onQuote(Quote quote) {
            quotes.add(quote);
        }
    }
}
