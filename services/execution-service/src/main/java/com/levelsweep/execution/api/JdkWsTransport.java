package com.levelsweep.execution.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDK 11+ {@code java.net.http.WebSocket} implementation of {@link WsTransport}.
 * Provider-agnostic — works for any WebSocket-based feed.
 *
 * <p>Each WS frame from the upstream may be split across multiple onText
 * callbacks (the {@code last} flag indicates the final fragment); we buffer
 * fragments and dispatch the full frame only when {@code last == true}. This
 * is the documented JDK behavior and we follow it exactly.
 *
 * <p>NOTE: Duplicates {@code com.levelsweep.marketdata.api.JdkWsTransport}
 * verbatim. See {@link WsTransport} for context on the planned
 * {@code shared-ws-transport} extraction.
 */
public final class JdkWsTransport implements WsTransport {

    private static final Logger LOG = LoggerFactory.getLogger(JdkWsTransport.class);

    private final URI endpoint;
    private final HttpClient http;
    private final Duration connectTimeout;
    private final Listener listener;
    private final ConcurrentLinkedDeque<CharSequence> fragments = new ConcurrentLinkedDeque<>();
    private volatile WebSocket webSocket;

    public JdkWsTransport(URI endpoint, HttpClient http, Duration connectTimeout, Listener listener) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.http = Objects.requireNonNull(http, "http");
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public CompletionStage<Void> connect() {
        WebSocket.Builder builder = http.newWebSocketBuilder().connectTimeout(connectTimeout);
        return builder.buildAsync(endpoint, new InternalHandler()).thenAccept(ws -> {
            this.webSocket = ws;
            listener.onOpen();
        });
    }

    @Override
    public CompletionStage<Void> send(String frame) {
        WebSocket ws = webSocket;
        if (ws == null) {
            return CompletableFuture.failedStage(new IllegalStateException("not connected"));
        }
        return ws.sendText(frame, true).thenApply(unused -> null);
    }

    @Override
    public CompletionStage<Void> close() {
        WebSocket ws = webSocket;
        if (ws == null) {
            return CompletableFuture.completedFuture(null);
        }
        return ws.sendClose(WebSocket.NORMAL_CLOSURE, "client closing").thenApply(unused -> null);
    }

    private final class InternalHandler implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragments.addLast(data);
            if (last) {
                StringBuilder sb = new StringBuilder();
                CharSequence chunk;
                while ((chunk = fragments.pollFirst()) != null) {
                    sb.append(chunk);
                }
                try {
                    listener.onText(sb.toString());
                } catch (Exception e) {
                    LOG.warn("listener.onText threw — continuing", e);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            listener.onError(error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            listener.onClose(statusCode, reason);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            // Alpaca trade-updates sends only text frames. Ignore binary.
            webSocket.request(1);
            return null;
        }
    }
}
