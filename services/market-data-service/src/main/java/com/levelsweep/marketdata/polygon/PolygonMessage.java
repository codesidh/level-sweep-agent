package com.levelsweep.marketdata.polygon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Polygon WebSocket message DTO. Polygon sends arrays of these per frame; we
 * parse element-by-element. Unknown fields are ignored to allow graceful
 * forward-compatibility with new fields the upstream may add.
 *
 * <p>Event types we care about for Phase 1:
 *
 * <ul>
 *   <li>{@code T} — trade tick (price + size + exchange timestamp)
 *   <li>{@code Q} — NBBO quote (bid/ask + sizes + timestamp)
 *   <li>{@code status} — auth / subscription confirmations and errors
 * </ul>
 *
 * <p>Other Polygon event types (A=second-aggregates, AM=minute-aggregates,
 * etc.) are silently dropped. Phase 1 builds bars from raw trades.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolygonMessage(
        @JsonProperty("ev") String event,
        @JsonProperty("sym") String symbol,
        // Trade fields
        @JsonProperty("p") Double price,
        @JsonProperty("s") Long size,
        @JsonProperty("t") Long timestampMillis,
        // Quote fields
        @JsonProperty("bp") Double bidPrice,
        @JsonProperty("bs") Long bidSize,
        @JsonProperty("ap") Double askPrice,
        @JsonProperty("as") Long askSize,
        // Status fields
        @JsonProperty("status") String status,
        @JsonProperty("message") String message) {

    public boolean isTrade() {
        return "T".equals(event);
    }

    public boolean isQuote() {
        return "Q".equals(event);
    }

    public boolean isStatus() {
        return "status".equals(event);
    }
}
