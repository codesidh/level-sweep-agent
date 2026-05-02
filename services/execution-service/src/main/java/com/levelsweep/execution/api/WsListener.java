package com.levelsweep.execution.api;

/**
 * Provider-agnostic sink for WebSocket lifecycle events. Mirrors the inner
 * {@link WsTransport.Listener} but exists as a top-level interface so callers
 * can declare it as a clean field type without nesting.
 *
 * <p>Currently unused as a separate seam (the trade-updates pipeline plumbs
 * directly into {@link WsTransport.Listener}); kept as a one-to-one mirror
 * with the market-data-service pattern so the eventual
 * {@code shared-ws-transport} module extraction does not need to invent a new
 * interface.
 */
public interface WsListener {

    void onText(String frame);

    void onError(Throwable cause);

    default void onClose(int code, String reason) {
        // default no-op
    }

    default void onOpen() {
        // default no-op
    }
}
