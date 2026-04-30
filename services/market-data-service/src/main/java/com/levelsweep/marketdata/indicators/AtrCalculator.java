package com.levelsweep.marketdata.indicators;

import com.levelsweep.shared.domain.marketdata.Bar;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Wilder's Average True Range. Phase 1 — used by the Decision Engine
 * (Phase 2) for ATR-buffer in §7 sweep validation and EMA-gap test in §6.1
 * of {@code requirements.md}.
 *
 * <p>True Range:
 *
 * <pre>
 *   TR_t = max(high - low,
 *              |high - prev_close|,
 *              |low - prev_close|)
 * </pre>
 *
 * <p>Wilder's smoothing:
 *
 * <pre>
 *   ATR_period = simple_average(TR over first {period} bars)
 *   ATR_t      = (ATR_{t-1} * (period - 1) + TR_t) / period
 * </pre>
 *
 * <p>Stateful: feed daily bars in chronological order. Returns {@code null}
 * during bootstrap (first {@code period} bars).
 */
public final class AtrCalculator {

    private static final MathContext MC = MathContext.DECIMAL64;

    private final int period;
    private final BigDecimal periodMinusOne;
    private final BigDecimal periodBd;
    private BigDecimal atr;
    private BigDecimal previousClose;
    private BigDecimal trSumForBootstrap = BigDecimal.ZERO;
    private int observed;

    public AtrCalculator(int period) {
        if (period <= 1) {
            throw new IllegalArgumentException("period must be > 1: " + period);
        }
        this.period = period;
        this.periodBd = BigDecimal.valueOf(period);
        this.periodMinusOne = BigDecimal.valueOf(period - 1L);
    }

    /** Feed a bar (must be in chronological order); returns the ATR or null during bootstrap. */
    public BigDecimal update(Bar bar) {
        if (bar == null) {
            throw new IllegalArgumentException("bar must not be null");
        }
        BigDecimal tr = trueRange(bar.high(), bar.low(), previousClose);
        previousClose = bar.close();
        observed++;
        if (observed < period) {
            trSumForBootstrap = trSumForBootstrap.add(tr, MC);
            return null;
        }
        if (observed == period) {
            trSumForBootstrap = trSumForBootstrap.add(tr, MC);
            atr = trSumForBootstrap.divide(periodBd, MC);
            return atr;
        }
        // Wilder smoothing: ATR_t = (ATR_{t-1} * (period-1) + TR_t) / period
        atr = atr.multiply(periodMinusOne, MC).add(tr, MC).divide(periodBd, MC);
        return atr;
    }

    public BigDecimal value() {
        return atr;
    }

    public boolean isReady() {
        return atr != null;
    }

    public int period() {
        return period;
    }

    /** Pure helper exposed for tests / external callers needing TR. */
    public static BigDecimal trueRange(BigDecimal high, BigDecimal low, BigDecimal prevClose) {
        BigDecimal hMinusL = high.subtract(low, MC).abs(MC);
        if (prevClose == null) {
            return hMinusL;
        }
        BigDecimal hMinusPc = high.subtract(prevClose, MC).abs(MC);
        BigDecimal lMinusPc = low.subtract(prevClose, MC).abs(MC);
        BigDecimal max = hMinusL;
        if (hMinusPc.compareTo(max) > 0) {
            max = hMinusPc;
        }
        if (lMinusPc.compareTo(max) > 0) {
            max = lMinusPc;
        }
        return max;
    }

    public static BigDecimal round(BigDecimal v, int scale) {
        return v == null ? null : v.setScale(scale, RoundingMode.HALF_UP);
    }
}
