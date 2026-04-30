package com.levelsweep.shared.domain.indicators;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Indicator values for a (symbol, timestamp) point. The Decision Engine in
 * Phase 2 consumes these to evaluate the EMA-stack validity per
 * {@code requirements.md} §6.
 *
 * <ul>
 *   <li>{@code ema13} / {@code ema48} / {@code ema200} — exponential moving
 *       averages on 2-min close prices
 *   <li>{@code atr14} — 14-period ATR on daily bars (Wilder's smoothing)
 * </ul>
 *
 * <p>Some fields may be {@code null} during warm-up — the EMAs require their
 * full bootstrap window before producing a meaningful value, and ATR requires
 * 14 daily bars.
 */
public record IndicatorSnapshot(
        String symbol, Instant timestamp, BigDecimal ema13, BigDecimal ema48, BigDecimal ema200, BigDecimal atr14) {

    public IndicatorSnapshot {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(timestamp, "timestamp");
        // Indicators may be null during warm-up; not validated here.
    }

    /** Whether all EMAs have completed their bootstrap window. */
    public boolean emasReady() {
        return ema13 != null && ema48 != null && ema200 != null;
    }

    /** Whether all indicators (EMAs + ATR) are ready. */
    public boolean fullyReady() {
        return emasReady() && atr14 != null;
    }
}
