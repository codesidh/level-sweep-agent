package com.levelsweep.marketdata.alpaca;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.marketdata.api.TickListener;
import com.levelsweep.marketdata.api.WsTransport;
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
 * Top-level orchestrator for the Alpaca WebSocket → tick-stream pipeline.
 *
 * <p>Composes:
 *
 * <ul>
 *   <li>{@link WsTransport} — pluggable transport (real WS in prod, stub in tests)
 *   <li>{@link AlpacaMessageDecoder} — JSON parsing
 *   <li>{@link ConnectionMonitor} — Connection FSM tracker
 *   <li>{@link TickRingBuffer} — backpressure absorption
 *   <li>{@link TickListener} — caller-supplied sink (the bar aggregator subscribes)
 * </ul>
 *
 * <p>Lifecycle (per {@code .claude/skills/alpaca-trading-api/SKILL.md}):
 *
 * <ol>
 *   <li>Caller invokes {@link #start()} — opens transport
 *   <li>On socket open, send auth message {@code {action,key,secret}}
 *   <li>WAIT for {@code success: authenticated} status before sending subscribe
 *       (Alpaca server may reject premature subscribe)
 *   <li>Send subscribe message: {@code {action,trades,quotes,bars}}
 *   <li>Inbound trade/quote frames are decoded; ticks land in the buffer AND
 *       are dispatched synchronously to the listener
 *   <li>{@link #stop()} closes the transport cleanly
 * </ol>
 *
 * <p>Reconnect / backoff is the caller's responsibility — this class exposes
 * Connection FSM state via {@link #connectionState()} so an outer supervisor
 * can decide when to re-create and re-{@link #start()}.
 */
public final class AlpacaStream {

    private static final Logger LOG = LoggerFactory.getLogger(AlpacaStream.class);

    private final WsTransport transport;
    private final AlpacaMessageDecoder decoder;
    private final ConnectionMonitor monitor;
    private final TickRingBuffer buffer;
    private final TickListener listener;
    private final List<String> symbols;
    private final String apiKey;
    private final String secretKey;
    private volatile boolean authSent;
    private volatile boolean subscribeSent;

    private AlpacaStream(Builder b) {
        this.transport = Objects.requireNonNull(b.transport, "transport");
        this.decoder = Objects.requireNonNull(b.decoder, "decoder");
        this.monitor = Objects.requireNonNull(b.monitor, "monitor");
        this.buffer = Objects.requireNonNull(b.buffer, "buffer");
        this.listener = Objects.requireNonNull(b.listener, "listener");
        this.symbols = Objects.requireNonNull(b.symbols, "symbols");
        this.apiKey = b.apiKey == null ? "" : b.apiKey;
        this.secretKey = b.secretKey == null ? "" : b.secretKey;
    }

    public static Builder builder() {
        return new Builder();
    }

    public CompletionStage<Void> start() {
        // Connect; the transport's onOpen callback (wired via createTransportListener
        // below) sends the auth frame. Subscribe waits for auth_success.
        return transport.connect();
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

    public ConnectionMonitor monitor() {
        return monitor;
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
            // Alpaca status flow:
            //   success: connected     -> server greeted us
            //   success: authenticated -> auth committed; safe to subscribe
            //   subscription:...       -> subscribe acknowledged
            //   error: ...             -> Connection FSM error
            if ("success".equals(status) && "authenticated".equals(message) && !subscribeSent) {
                subscribeSent = true;
                sendSubscribe();
            } else if ("error".equals(status)) {
                monitor.recordError(new RuntimeException("alpaca status error: " + message));
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
                if (!authSent) {
                    authSent = true;
                    sendAuth();
                }
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
                authSent = false;
                subscribeSent = false;
            }
        };
    }

    private void sendAuth() {
        // Alpaca auth: {"action":"auth","key":"<key>","secret":"<secret>"}
        String escapedKey = jsonEscape(apiKey);
        String escapedSecret = jsonEscape(secretKey);
        String auth = "{\"action\":\"auth\",\"key\":\"" + escapedKey + "\",\"secret\":\"" + escapedSecret + "\"}";
        transport.send(auth).exceptionally(t -> {
            LOG.warn("send auth failed", t);
            return null;
        });
    }

    private void sendSubscribe() {
        // Alpaca subscribe: {"action":"subscribe","trades":["SPY"],"quotes":["SPY"],"bars":["SPY"]}
        StringBuilder sb = new StringBuilder("{\"action\":\"subscribe\",\"trades\":[");
        appendQuotedCsv(sb, symbols);
        sb.append("],\"quotes\":[");
        appendQuotedCsv(sb, symbols);
        sb.append("],\"bars\":[");
        appendQuotedCsv(sb, symbols);
        sb.append("]}");
        transport.send(sb.toString()).exceptionally(t -> {
            LOG.warn("send subscribe failed", t);
            return null;
        });
    }

    private static void appendQuotedCsv(StringBuilder sb, List<String> items) {
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(jsonEscape(items.get(i))).append('"');
        }
    }

    private static String jsonEscape(String s) {
        // Symbols and API keys are ASCII alphanumeric; basic escape for safety.
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static final class Builder {
        private WsTransport transport;
        private AlpacaMessageDecoder decoder;
        private ConnectionMonitor monitor;
        private TickRingBuffer buffer;
        private TickListener listener;
        private List<String> symbols;
        private String apiKey;
        private String secretKey;

        public Builder transport(WsTransport t) {
            this.transport = t;
            return this;
        }

        public Builder decoder(AlpacaMessageDecoder d) {
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

        public Builder secretKey(String secret) {
            this.secretKey = secret;
            return this;
        }

        public AlpacaStream build() {
            if (decoder == null) {
                decoder = new AlpacaMessageDecoder(new ObjectMapper());
            }
            return new AlpacaStream(this);
        }
    }

    public static Duration defaultConnectTimeout() {
        return Duration.ofSeconds(5);
    }

    public static TickListener noopListener() {
        return new TickListener() {
            @Override
            public void onTick(Tick tick) {}

            @Override
            public void onQuote(Quote quote) {}
        };
    }

    public static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
