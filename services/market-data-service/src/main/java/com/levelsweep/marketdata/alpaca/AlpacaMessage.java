package com.levelsweep.marketdata.alpaca;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Alpaca Market Data WebSocket message DTO. Alpaca sends arrays of these
 * per frame; we parse element-by-element. Unknown fields are ignored to
 * allow forward-compatibility with new fields.
 *
 * <p>Discriminator: the {@code T} field identifies the message kind:
 *
 * <ul>
 *   <li>{@code t} — trade tick (price + size + ISO-8601 timestamp)
 *   <li>{@code q} — NBBO quote (bid/ask + sizes + ISO-8601 timestamp)
 *   <li>{@code b} — minute aggregate bar (OHLCV + timestamp)
 *   <li>{@code success} — auth / connection status (msg = "connected" / "authenticated")
 *   <li>{@code subscription} — subscription confirmation (echo of subscribed symbol arrays)
 *   <li>{@code error} — error event (code + msg)
 * </ul>
 *
 * <p>The {@code c} field is intentionally typed as {@link Object} because
 * Alpaca overloads it: a {@code Double} on bar messages (close price) and
 * a list of condition codes on trade messages. The decoder casts based on
 * the discriminator.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlpacaMessage(
        @JsonProperty("T") String type,
        @JsonProperty("S") String symbol,
        // Trade fields
        @JsonProperty("p") Double price,
        @JsonProperty("s") Long size,
        @JsonProperty("x") String exchange,
        @JsonProperty("i") Long tradeId,
        // Quote fields
        @JsonProperty("bp") Double bidPrice,
        @JsonProperty("bs") Long bidSize,
        @JsonProperty("ap") Double askPrice,
        @JsonProperty("as") Long askSize,
        @JsonProperty("bx") String bidExchange,
        @JsonProperty("ax") String askExchange,
        // Bar fields
        @JsonProperty("o") Double open,
        @JsonProperty("h") Double high,
        @JsonProperty("l") Double low,
        @JsonProperty("v") Long volume,
        // Overloaded: bar.close (Double) OR trade.conditions (List<String>)
        @JsonProperty("c") Object closeOrConditions,
        // Common
        @JsonProperty("t") String timestamp,
        // Status / error fields
        @JsonProperty("msg") String message,
        @JsonProperty("code") Integer code,
        // Subscription confirmation
        @JsonProperty("trades") List<String> tradeSubs,
        @JsonProperty("quotes") List<String> quoteSubs,
        @JsonProperty("bars") List<String> barSubs) {

    public boolean isTrade() {
        return "t".equals(type);
    }

    public boolean isQuote() {
        return "q".equals(type);
    }

    public boolean isBar() {
        return "b".equals(type);
    }

    public boolean isSuccess() {
        return "success".equals(type);
    }

    public boolean isSubscription() {
        return "subscription".equals(type);
    }

    public boolean isError() {
        return "error".equals(type);
    }

    /** Bar close price — only valid when {@link #isBar()} is true. */
    public Double barClose() {
        return closeOrConditions instanceof Double d ? d : null;
    }
}
