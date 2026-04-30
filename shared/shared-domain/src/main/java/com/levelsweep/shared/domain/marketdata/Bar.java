package com.levelsweep.shared.domain.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * OHLCV bar for a given timeframe.
 *
 * <p>Bars are immutable; the bar aggregator emits a new {@code Bar} when a
 * timeframe boundary closes. Replay-deterministic: open/close instants are
 * exchange wall-clock (UTC), aligned per {@link Timeframe#floor}.
 *
 * <p>Invariants:
 *
 * <ul>
 *   <li>{@code closeTime > openTime} (strictly later)
 *   <li>{@code low ≤ min(open, close, high)} and {@code high ≥ max(open, close, low)}
 *   <li>{@code volume ≥ 0} and {@code ticks ≥ 0}
 * </ul>
 */
public record Bar(
        String symbol,
        Timeframe timeframe,
        Instant openTime,
        Instant closeTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        long ticks) {

    public Bar {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(timeframe, "timeframe");
        Objects.requireNonNull(openTime, "openTime");
        Objects.requireNonNull(closeTime, "closeTime");
        Objects.requireNonNull(open, "open");
        Objects.requireNonNull(high, "high");
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(close, "close");
        if (!closeTime.isAfter(openTime)) {
            throw new IllegalArgumentException(
                    "closeTime must be after openTime: open=" + openTime + " close=" + closeTime);
        }
        if (volume < 0) {
            throw new IllegalArgumentException("volume must be non-negative: " + volume);
        }
        if (ticks < 0) {
            throw new IllegalArgumentException("ticks must be non-negative: " + ticks);
        }
        if (low.compareTo(high) > 0) {
            throw new IllegalArgumentException("low > high: low=" + low + " high=" + high);
        }
        if (low.compareTo(open) > 0
                || low.compareTo(close) > 0
                || high.compareTo(open) < 0
                || high.compareTo(close) < 0) {
            throw new IllegalArgumentException("low/high must envelope open/close: open="
                    + open
                    + " close="
                    + close
                    + " low="
                    + low
                    + " high="
                    + high);
        }
    }
}
