package com.levelsweep.marketdata.polygon;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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

class PolygonStreamTest {

    private StubTransport transport;
    private TickRingBuffer buffer;
    private ConnectionMonitor monitor;
    private RecordingListener listener;
    private PolygonStream stream;

    @BeforeEach
    void setUp() {
        transport = new StubTransport();
        buffer = new TickRingBuffer(1000);
        TestClock clock = new TestClock(Instant.parse("2026-04-30T13:30:00Z"));
        monitor = new ConnectionMonitor("polygon", clock);
        listener = new RecordingListener();
        stream =
                PolygonStream.builder()
                        .transport(transport)
                        .decoder(new PolygonMessageDecoder(new ObjectMapper()))
                        .monitor(monitor)
                        .buffer(buffer)
                        .listener(listener)
                        .symbols(List.of("SPY"))
                        .apiKey("test-key")
                        .build();
        // Wire transport listener
        transport.setListener(stream.createTransportListener());
    }

    @Test
    void startSendsAuthThenSubscribe() {
        PolygonStream.await(stream.start());
        assertThat(transport.opened).isTrue();
        assertThat(transport.sentFrames).hasSize(2);
        assertThat(transport.sentFrames.get(0)).contains("\"action\":\"auth\"");
        assertThat(transport.sentFrames.get(0)).contains("test-key");
        assertThat(transport.sentFrames.get(1)).contains("\"action\":\"subscribe\"");
        assertThat(transport.sentFrames.get(1)).contains("T.SPY,Q.SPY");
    }

    @Test
    void incomingTradeFrameLandsInBufferAndListener() {
        PolygonStream.await(stream.start());
        transport.deliver(
                "[{\"ev\":\"T\",\"sym\":\"SPY\",\"p\":594.23,\"s\":100,\"t\":1714492800000}]");
        assertThat(listener.ticks).hasSize(1);
        assertThat(buffer.size()).isEqualTo(1);
    }

    @Test
    void incomingQuoteDoesNotPopulateBuffer() {
        // The buffer is for ticks only; quotes go straight to the listener (Phase 3 trail uses them).
        PolygonStream.await(stream.start());
        transport.deliver(
                "[{\"ev\":\"Q\",\"sym\":\"SPY\",\"bp\":594.20,\"bs\":100,\"ap\":594.25,\"as\":200,\"t\":1714492800001}]");
        assertThat(listener.quotes).hasSize(1);
        assertThat(buffer.size()).isZero();
    }

    @Test
    void onOpenMarksMonitorHealthy() {
        // Pre-open, monitor starts HEALTHY but onOpen explicitly resets — verify by going DEGRADED then re-opening.
        for (int i = 0; i < 3; i++) {
            transport.failWith(new RuntimeException("transient " + i));
        }
        assertThat(monitor.state()).isEqualTo(ConnectionState.DEGRADED);
        // Simulate reconnect — onOpen fires
        transport.fireOpen();
        assertThat(monitor.state()).isEqualTo(ConnectionState.HEALTHY);
    }

    @Test
    void transportErrorRecordsInMonitor() {
        PolygonStream.await(stream.start());
        for (int i = 0; i < 5; i++) {
            transport.failWith(new RuntimeException("e" + i));
        }
        assertThat(monitor.state()).isEqualTo(ConnectionState.UNHEALTHY);
    }

    @Test
    void statusErrorIsTreatedAsConnectionError() {
        PolygonStream.await(stream.start());
        for (int i = 0; i < 5; i++) {
            transport.deliver(
                    "[{\"ev\":\"status\",\"status\":\"error\",\"message\":\"bad request "
                            + i
                            + "\"}]");
        }
        assertThat(monitor.state()).isEqualTo(ConnectionState.UNHEALTHY);
    }

    @Test
    void downstreamListenerExceptionDoesNotBreakPipeline() {
        TickListener exploding =
                new TickListener() {
                    @Override
                    public void onTick(Tick tick) {
                        throw new RuntimeException("kaboom");
                    }

                    @Override
                    public void onQuote(Quote quote) {
                        // no-op
                    }
                };
        PolygonStream s2 =
                PolygonStream.builder()
                        .transport(transport)
                        .decoder(new PolygonMessageDecoder(new ObjectMapper()))
                        .monitor(monitor)
                        .buffer(buffer)
                        .listener(exploding)
                        .symbols(List.of("SPY"))
                        .apiKey("test-key")
                        .build();
        transport.setListener(s2.createTransportListener());
        PolygonStream.await(s2.start());
        // Should not throw
        transport.deliver(
                "[{\"ev\":\"T\",\"sym\":\"SPY\",\"p\":594.23,\"s\":100,\"t\":1714492800000}]");
        // Buffer still received it
        assertThat(buffer.size()).isEqualTo(1);
    }

    @Test
    void stopClosesTransport() {
        PolygonStream.await(stream.start());
        PolygonStream.await(stream.stop());
        assertThat(transport.closed).isTrue();
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

    @Test
    void buildsWithDefaultDecoder() {
        // Ensure builder works without explicitly providing a decoder
        PolygonStream s =
                PolygonStream.builder()
                        .transport(new StubTransport())
                        .monitor(new ConnectionMonitor("polygon", Clock.systemUTC()))
                        .buffer(new TickRingBuffer(10))
                        .listener(PolygonStream.noopListener())
                        .symbols(List.of("SPY"))
                        .apiKey("k")
                        .build();
        assertThat(s).isNotNull();
        assertThat(s.connectionState()).isEqualTo(ConnectionState.HEALTHY);
        assertThat(s.monitor().dependency()).isEqualTo("polygon");
        assertThat(PolygonStream.defaultConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
    }
}
