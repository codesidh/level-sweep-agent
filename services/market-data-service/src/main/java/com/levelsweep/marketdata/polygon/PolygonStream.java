package com.levelsweep.marketdata.polygon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.marketdata.buffer.TickRingBuffer;
import com.levelsweep.marketdata.connection.ConnectionMonitor;
import com.levelsweep.marketdata.connection.ConnectionState;
import com.levelsweep.shared.domain.marketdata.Quote;
import com.levelsweep.shared.domain.marketdata.Tick;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Top-level orchestrator for the Polygon WebSocket → tick-stream pipeline.
 *
 * <p>Composes:
 *
 * <ul>
 *   <li>{@link WsTransport} — pluggable transport (real WS in prod, stub in tests)
 *   <li>{@link PolygonMessageDecoder} — JSON parsing
 *   <li>{@link ConnectionMonitor} — Connection FSM tracker
 *   <li>{@link TickRingBuffer} — backpressure absorption
 *   <li>{@link TickListener} — caller-supplied sink (the bar aggregator in #11)
 * </ul>
 *
 * <p>Lifecycle:
 *
 * <ol>
 *   <li>Caller invokes {@link #start()} — opens transport, sends auth, then subscribe
 *   <li>Inbound frames are decoded; ticks land in the buffer AND are dispatched
 *       synchronously to the listener (for low-latency consumers; the buffer
 *       absorbs slowness if the listener stalls)
 *   <li>{@link #stop()} closes the transport cleanly
 * </ol>
 *
 * <p>Reconnect / backoff is the caller's responsibility — this class exposes
 * Connection FSM state via {@link #connectionState()} so an outer supervisor
 * can decide when to re-create and re-{@link #start()}.
 */
public final class PolygonStream {

    private static final Logger LOG = LoggerFactory.getLogger(PolygonStream.class);

    private final WsTransport transport;
    private final PolygonMessageDecoder decoder;
    private final ConnectionMonitor monitor;
    private final TickRingBuffer buffer;
    private final TickListener listener;
    private final List<String> symbols;
    private final String apiKey;

    private PolygonStream(Builder b) {
        this.transport = Objects.requireNonNull(b.transport, "transport");
        this.decoder = Objects.requireNonNull(b.decoder, "decoder");
        this.monitor = Objects.requireNonNull(b.monitor, "monitor");
        this.buffer = Objects.requireNonNull(b.buffer, "buffer");
        this.listener = Objects.requireNonNull(b.listener, "listener");
        this.symbols = Objects.requireNonNull(b.symbols, "symbols");
        this.apiKey = b.apiKey == null ? "" : b.apiKey;
    }

    public static Builder builder() {
        return new Builder();
    }

    public CompletionStage<Void> start() {
        return transport.connect().thenCompose(unused -> sendHandshake());
    }

    public CompletionStage<Void> stop() {
        return transport.close();
    }

    public ConnectionState connectionState() {
        return monitor.state();
    }

    public TickRingBuffer buffer() {
        return buffer;
    }

    /** Internal listener that decoder dispatches into. Wraps caller listener and buffer. */
    final class InternalListener implements TickListener {
        @Override
        public void onTick(Tick tick) {
            buffer.offer(tick);
            try {
                listener.onTick(tick);
            } catch (Exception e) {
                LOG.warn("downstream onTick threw — continuing", e);
            }
        }

        @Override
        public void onQuote(Quote quote) {
            try {
                listener.onQuote(quote);
            } catch (Exception e) {
                LOG.warn("downstream onQuote threw — continuing", e);
            }
        }

        @Override
        public void onStatus(String status, String message) {
            // Polygon emits status frames for auth_success / subscription confirmations.
            // We treat any explicit error status as a Connection FSM error.
            if (status != null && status.toLowerCase().contains("error")) {
                monitor.recordError(new RuntimeException("polygon status error: " + message));
            }
            listener.onStatus(status, message);
        }
    }

    /** Plug a {@link WsTransport.Listener} that wires the decoder + monitor. */
    public WsTransport.Listener createTransportListener() {
        InternalListener il = new InternalListener();
        return new WsTransport.Listener() {
            @Override
            public void onOpen() {
                monitor.recordSuccess();
            }

            @Override
            public void onText(String frame) {
                decoder.decode(frame, il);
            }

            @Override
            public void onError(Throwable cause) {
                monitor.recordError(cause);
                LOG.warn("ws error: {}", cause.toString());
            }

            @Override
            public void onClose(int code, String reason) {
                LOG.info("ws closed: code={} reason={}", code, reason);
            }
        };
    }

    private CompletionStage<Void> sendHandshake() {
        // Polygon expects: auth → wait for auth_success → subscribe.
        // We send subscribe immediately after auth without waiting; Polygon queues
        // the subscribe until auth completes server-side and answers in order.
        String auth = String.format("{\"action\":\"auth\",\"params\":\"%s\"}", apiKey);
        String subscribeParams =
                String.join(",", symbols.stream().map(s -> "T." + s + ",Q." + s).toList());
        String subscribe = String.format("{\"action\":\"subscribe\",\"params\":\"%s\"}", subscribeParams);
        return transport.send(auth).thenCompose(u -> transport.send(subscribe));
    }

    public static final class Builder {
        private WsTransport transport;
        private PolygonMessageDecoder decoder;
        private ConnectionMonitor monitor;
        private TickRingBuffer buffer;
        private TickListener listener;
        private List<String> symbols;
        private String apiKey;

        public Builder transport(WsTransport t) {
            this.transport = t;
            return this;
        }

        public Builder decoder(PolygonMessageDecoder d) {
            this.decoder = d;
            return this;
        }

        public Builder monitor(ConnectionMonitor m) {
            this.monitor = m;
            return this;
        }

        public Builder buffer(TickRingBuffer b) {
            this.buffer = b;
            return this;
        }

        public Builder listener(TickListener l) {
            this.listener = l;
            return this;
        }

        public Builder symbols(List<String> s) {
            this.symbols = s;
            return this;
        }

        public Builder apiKey(String key) {
            this.apiKey = key;
            return this;
        }

        public PolygonStream build() {
            if (decoder == null) {
                decoder = new PolygonMessageDecoder(new ObjectMapper());
            }
            return new PolygonStream(this);
        }
    }

    /** Small helper used by callers in unit tests. */
    public static Duration defaultConnectTimeout() {
        return Duration.ofSeconds(5);
    }

    /** Convenience for tests that need a no-op listener. */
    public static TickListener noopListener() {
        return new TickListener() {
            @Override
            public void onTick(Tick tick) {
                // no-op
            }

            @Override
            public void onQuote(Quote quote) {
                // no-op
            }
        };
    }

    /** Synchronous future helper for tests. */
    public static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    /** Used by a future Phase 7 reconnect supervisor. */
    public ConnectionMonitor monitor() {
        return monitor;
    }
}
