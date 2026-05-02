package com.levelsweep.execution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Minimal unit tests for {@link JdkWsTransport}. The class is mostly a thin
 * delegation onto {@link java.net.http.HttpClient#newWebSocketBuilder()}; we do
 * NOT spin up a real WebSocket server here. Instead we verify the small surface
 * that has logic of its own:
 *
 * <ul>
 *   <li>The constructor enforces non-null arguments.
 *   <li>{@code send} fails fast (a failed {@code CompletionStage}) when the
 *       transport has not yet completed {@link JdkWsTransport#connect()}.
 *   <li>{@code close} is idempotent and safe to call before any connect.
 * </ul>
 *
 * <p>End-to-end verification of the JDK {@link java.net.http.WebSocket} happens
 * in the per-phase soak environment against the real Alpaca trade-updates
 * endpoint (architecture-spec §21.1). The {@link AlpacaTradeUpdatesStreamTest}
 * exercises the orchestration logic via the {@link WsTransport} seam.
 */
class JdkWsTransportTest {

    private static final URI ENDPOINT = URI.create("wss://paper-api.alpaca.markets/stream");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private static WsTransport.Listener noopListener() {
        return new WsTransport.Listener() {
            @Override
            public void onText(String frame) {}

            @Override
            public void onError(Throwable cause) {}
        };
    }

    @Test
    void constructorRejectsNullEndpoint() {
        assertThatThrownBy(() -> new JdkWsTransport(null, HttpClient.newHttpClient(), CONNECT_TIMEOUT, noopListener()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNullHttpClient() {
        assertThatThrownBy(() -> new JdkWsTransport(ENDPOINT, null, CONNECT_TIMEOUT, noopListener()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNullConnectTimeout() {
        assertThatThrownBy(() -> new JdkWsTransport(ENDPOINT, HttpClient.newHttpClient(), null, noopListener()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNullListener() {
        assertThatThrownBy(() -> new JdkWsTransport(ENDPOINT, HttpClient.newHttpClient(), CONNECT_TIMEOUT, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void sendBeforeConnectReturnsFailedStage() {
        JdkWsTransport t = new JdkWsTransport(ENDPOINT, HttpClient.newHttpClient(), CONNECT_TIMEOUT, noopListener());

        // Per the contract: send() returns a failed CompletionStage with an
        // IllegalStateException when the transport has not yet connected,
        // rather than throwing synchronously.
        var stage = t.send("noop").toCompletableFuture();

        assertThat(stage).isCompletedExceptionally();
        assertThatThrownBy(stage::join)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not connected");
    }

    @Test
    void closeBeforeConnectIsIdempotentNoOp() {
        JdkWsTransport t = new JdkWsTransport(ENDPOINT, HttpClient.newHttpClient(), CONNECT_TIMEOUT, noopListener());

        // Closing before any connect must not throw — the lifecycle bean
        // calls stop() in @ShutdownEvent regardless of whether start() ran.
        var first = t.close().toCompletableFuture();
        var second = t.close().toCompletableFuture();

        assertThat(first).isCompleted();
        assertThat(second).isCompleted();
    }
}
