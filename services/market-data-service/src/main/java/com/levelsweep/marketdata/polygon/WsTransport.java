package com.levelsweep.marketdata.polygon;

import java.util.concurrent.CompletionStage;

/**
 * Abstraction over a WebSocket transport. Production uses the JDK 11+
 * {@link java.net.http.HttpClient.WebSocket} via {@link JdkWsTransport};
 * tests substitute a stub that emits canned frames.
 *
 * <p>Decoupling lets us unit-test the parsing + dispatch + Connection-FSM
 * logic without standing up an actual WebSocket server. End-to-end
 * verification against a real Polygon WS endpoint is deferred to integration
 * tests once the paid-tier subscription lands (issue #6).
 */
public interface WsTransport {

    /** Open the connection. */
    CompletionStage<Void> connect();

    /** Send a text frame (used for auth + subscribe handshake). */
    CompletionStage<Void> send(String frame);

    /** Close the connection cleanly. */
    CompletionStage<Void> close();

    /** Listener interface for inbound frames + lifecycle events. */
    interface Listener {
        void onText(String frame);

        void onError(Throwable cause);

        default void onClose(int code, String reason) {
            // default no-op
        }

        default void onOpen() {
            // default no-op
        }
    }
}
