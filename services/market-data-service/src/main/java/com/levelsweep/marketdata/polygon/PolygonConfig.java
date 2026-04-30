package com.levelsweep.marketdata.polygon;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.List;

/**
 * Configuration for the Polygon WebSocket adapter, sourced from
 * {@code application.yml} under the {@code polygon} prefix.
 *
 * <p>API key is intentionally left as a string property and expected to be
 * populated via env var {@code POLYGON_API_KEY} (which is sourced from Azure
 * Key Vault in non-dev environments per the trading-system-guardrails skill).
 * The key is never logged.
 */
@ConfigMapping(prefix = "polygon")
public interface PolygonConfig {

    /** Polygon WebSocket endpoint, e.g. {@code wss://socket.polygon.io/stocks}. */
    @WithDefault("wss://socket.polygon.io/stocks")
    String wsUrl();

    /** Polygon REST base URL (used for OHLC reconstruction fallback). */
    @WithDefault("https://api.polygon.io")
    String restUrl();

    /**
     * API key. Empty in dev / replay; required in stage and prod (validated at
     * {@code Application#run} time, not on hot path).
     */
    @WithDefault("")
    String apiKey();

    /** Symbols to subscribe. Phase 1 ships with SPY only. */
    @WithDefault("SPY")
    List<String> symbols();

    /** Initial reconnect backoff. Doubles up to {@link #reconnectMaxBackoff()}. */
    @WithDefault("PT0.2S")
    Duration reconnectInitialBackoff();

    /** Cap on reconnect backoff. */
    @WithDefault("PT30S")
    Duration reconnectMaxBackoff();

    /** Jitter range applied to each reconnect attempt. */
    @WithDefault("PT0.1S")
    Duration reconnectJitter();

    /** Tick ring-buffer capacity (must hold ~5 minutes of worst-case ticks). */
    @WithDefault("300000")
    int ringBufferCapacity();
}
