package com.levelsweep.marketdata.alpaca;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.List;

/**
 * Configuration for the Alpaca Market Data adapter, sourced from
 * {@code application.yml} under the {@code alpaca} prefix.
 *
 * <p>API key + secret are intentionally left as string properties and
 * expected to be populated via env vars {@code ALPACA_API_KEY} and
 * {@code ALPACA_SECRET_KEY} (sourced from Azure Key Vault in non-dev
 * environments per the trading-system-guardrails skill). Neither is logged.
 *
 * <p>Endpoint defaults match the production SIP feed; the {@code feed}
 * property selects between {@code sip} (paid, consolidated) and {@code iex}
 * (free, IEX-only). Sandbox endpoints are available for entitlement-free
 * testing — see ADR-0003 / `docs/alpaca-trading-api-skill.md`.
 */
@ConfigMapping(prefix = "alpaca")
public interface AlpacaConfig {

    /** Base WebSocket endpoint for stocks data. */
    @WithDefault("wss://stream.data.alpaca.markets")
    String wsBaseUrl();

    /** Stocks feed: "sip" for consolidated tape (paid), "iex" for IEX-only (free). */
    @WithDefault("sip")
    String feed();

    /** REST API base URL for trading endpoints (paper or live). */
    @WithDefault("https://paper-api.alpaca.markets")
    String tradingUrl();

    /** REST API base URL for market data. */
    @WithDefault("https://data.alpaca.markets")
    String dataUrl();

    /**
     * API key. Empty in dev / replay; required in stage and prod (validated
     * at {@code Application#run} time, not on hot path).
     */
    @WithDefault("")
    String apiKey();

    /** API secret (paired with the key for both REST headers and WS auth message). */
    @WithDefault("")
    String secretKey();

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

    /** Computed full WS URL — convenience accessor. */
    default String wsUrl() {
        return wsBaseUrl() + "/v2/" + feed();
    }
}
