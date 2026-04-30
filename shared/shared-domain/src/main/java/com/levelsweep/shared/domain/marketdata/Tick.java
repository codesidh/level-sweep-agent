package com.levelsweep.shared.domain.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable representation of a single trade tick from the market data feed.
 *
 * <p>Phase 1 (data layer) — consumed by the bar aggregator to build 1-min bars.
 * Replay-deterministic: the timestamp is exchange-timestamp (when the trade
 * cleared) and never derived from {@link Instant#now()}.
 */
public record Tick(String symbol, BigDecimal price, long size, Instant timestamp) {

    public Tick {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(timestamp, "timestamp");
        if (price.signum() < 0) {
            throw new IllegalArgumentException("price must be non-negative: " + price);
        }
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative: " + size);
        }
    }
}
