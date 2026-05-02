package com.levelsweep.execution.fill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.execution.api.WsTransport;
import com.levelsweep.shared.domain.trade.TradeFillEvent;
import com.levelsweep.shared.domain.trade.TradeFilled;
import jakarta.enterprise.event.Event;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Top-level orchestrator for the Alpaca trade-updates WebSocket → fill-event
 * pipeline.
 *
 * <p>Composes:
 *
 * <ul>
 *   <li>{@link WsTransport} — pluggable transport (real WS in prod, stub in tests)
 *   <li>{@link AlpacaTradeUpdatesDecoder} — JSON parsing
 *   <li>{@link ConnectionMonitor} — Connection FSM tracker
 *   <li>{@link Event} — CDI bus for {@link TradeFilled} / {@link TradeFillEvent} fan-out
 * </ul>
 *
 * <p>Lifecycle:
 *
 * <ol>
 *   <li>Caller invokes {@link #start()} — opens transport
 *   <li>On socket open, send authenticate message:
 *       {@code {"action":"authenticate","data":{"key_id":"...","secret_key":"..."}}}
 *       — note this differs from the stocks-data WS pattern
 *       (stocks uses {@code {"action":"auth","key":"...","secret":"..."}}).
 *   <li>WAIT for {@code stream:authorization, status:authorized} before sending listen
 *   <li>Send listen message: {@code {"action":"listen","data":{"streams":["trade_updates"]}}}
 *   <li>Inbound trade-update frames are decoded; a {@link TradeFilled} fires for
 *       every {@code fill}/{@code partial_fill}, a {@link TradeFillEvent} fires
 *       for every event (including non-fills).
 *   <li>{@link #stop()} closes the transport cleanly
 * </ol>
 *
 * <p>Reconnect / backoff is the caller's responsibility — this class exposes
 * Connection FSM state via {@link #connectionState()} so an outer supervisor
 * can decide when to re-create and re-{@link #start()}. Mirrors
 * {@code com.levelsweep.marketdata.alpaca.AlpacaStream}.
 */
public final class AlpacaTradeUpdatesStream {

    private static final Logger LOG = LoggerFactory.getLogger(AlpacaTradeUpdatesStream.class);

    private final WsTransport transport;
    private final AlpacaTradeUpdatesDecoder decoder;
    private final ConnectionMonitor monitor;
    private final Event<TradeFilled> tradeFilledEvent;
    private final Event<TradeFillEvent> fillEventBus;
    private final String apiKey;
    private final String secretKey;
    private volatile boolean authSent;
    private volatile boolean listenSent;

    private AlpacaTradeUpdatesStream(Builder b) {
        this.transport = Objects.requireNonNull(b.transport, "transport");
        this.decoder = Objects.requireNonNull(b.decoder, "decoder");
        this.monitor = Objects.requireNonNull(b.monitor, "monitor");
        this.tradeFilledEvent = b.tradeFilledEvent;
        this.fillEventBus = b.fillEventBus;
        this.apiKey = b.apiKey == null ? "" : b.apiKey;
        this.secretKey = b.secretKey == null ? "" : b.secretKey;
    }

    public static Builder builder() {
        return new Builder();
    }

    public CompletionStage<Void> start() {
        return transport.connect();
    }

    public CompletionStage<Void> stop() {
        return transport.close();
    }

    public ConnectionMonitor.State connectionState() {
        return monitor.state();
    }

    public ConnectionMonitor monitor() {
        return monitor;
    }

    /** Internal listener that the decoder dispatches into. */
    final class InternalListener implements AlpacaTradeUpdatesDecoder.Listener {
        @Override
        public void onTradeFilled(TradeFilled filled) {
            try {
                if (tradeFilledEvent != null) {
                    tradeFilledEvent.fire(filled);
                }
            } catch (Exception e) {
                LOG.warn("downstream TradeFilled observer threw — continuing", e);
            }
        }

        @Override
        public void onFillEvent(TradeFillEvent event) {
            try {
                if (fillEventBus != null) {
                    fillEventBus.fire(event);
                }
            } catch (Exception e) {
                LOG.warn("downstream TradeFillEvent observer threw — continuing", e);
            }
        }

        @Override
        public void onStatus(String stream, String status, String message) {
            // Trade-updates auth flow:
            //   stream:authorization, status:authorized   -> auth committed; safe to listen
            //   stream:authorization, status:unauthorized -> auth failed; trip Connection FSM
            //   stream:listening                          -> listen acknowledged
            if ("authorization".equals(stream)) {
                if ("authorized".equals(status) && !listenSent) {
                    listenSent = true;
                    sendListen();
                } else if (!"authorized".equals(status)) {
                    monitor.recordError(new RuntimeException(
                            "alpaca trade-updates auth failed: status=" + status + " message=" + message));
                }
            }
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
                listenSent = false;
            }
        };
    }

    private void sendAuth() {
        // Trade-updates auth:
        //   {"action":"authenticate","data":{"key_id":"<key>","secret_key":"<secret>"}}
        // Note the difference from stocks WS auth, which uses:
        //   {"action":"auth","key":"<key>","secret":"<secret>"}
        String escapedKey = jsonEscape(apiKey);
        String escapedSecret = jsonEscape(secretKey);
        String auth = "{\"action\":\"authenticate\",\"data\":{\"key_id\":\"" + escapedKey + "\",\"secret_key\":\""
                + escapedSecret + "\"}}";
        transport.send(auth).exceptionally(t -> {
            LOG.warn("send authenticate failed", t);
            return null;
        });
    }

    private void sendListen() {
        // Trade-updates listen: {"action":"listen","data":{"streams":["trade_updates"]}}
        String listen = "{\"action\":\"listen\",\"data\":{\"streams\":[\"trade_updates\"]}}";
        transport.send(listen).exceptionally(t -> {
            LOG.warn("send listen failed", t);
            return null;
        });
    }

    private static String jsonEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static final class Builder {
        private WsTransport transport;
        private AlpacaTradeUpdatesDecoder decoder;
        private ConnectionMonitor monitor;
        private Event<TradeFilled> tradeFilledEvent;
        private Event<TradeFillEvent> fillEventBus;
        private String apiKey;
        private String secretKey;

        public Builder transport(WsTransport t) {
            this.transport = t;
            return this;
        }

        public Builder decoder(AlpacaTradeUpdatesDecoder d) {
            this.decoder = d;
            return this;
        }

        public Builder monitor(ConnectionMonitor m) {
            this.monitor = m;
            return this;
        }

        public Builder tradeFilledEvent(Event<TradeFilled> e) {
            this.tradeFilledEvent = e;
            return this;
        }

        public Builder fillEventBus(Event<TradeFillEvent> e) {
            this.fillEventBus = e;
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

        public AlpacaTradeUpdatesStream build() {
            if (decoder == null) {
                decoder = new AlpacaTradeUpdatesDecoder(new ObjectMapper());
            }
            return new AlpacaTradeUpdatesStream(this);
        }
    }

    public static Duration defaultConnectTimeout() {
        return Duration.ofSeconds(5);
    }

    public static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
