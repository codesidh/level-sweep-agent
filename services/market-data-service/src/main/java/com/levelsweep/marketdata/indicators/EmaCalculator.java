package com.levelsweep.marketdata.indicators;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Stateful exponential moving average calculator.
 *
 * <p>Bootstrap: until {@code n} samples have been observed, the running
 * value is a simple average of those samples. On the {@code n}-th sample
 * the SMA value becomes the seed for EMA smoothing thereafter.
 *
 * <p>Standard EMA recurrence:
 *
 * <pre>
 *   alpha = 2 / (n + 1)
 *   ema_t = (close_t - ema_{t-1}) * alpha + ema_{t-1}
 * </pre>
 *
 * <p>Numeric precision: BigDecimal with {@link MathContext#DECIMAL64} (~16
 * digits). Sufficient for trading prices (4–6 significant digits) with no
 * accumulating drift over a typical session.
 *
 * <p>Replay-deterministic — pure stateful function; same input sequence
 * always produces the same output.
 */
public final class EmaCalculator {

    private static final MathContext MC = MathContext.DECIMAL64;

    private final int period;
    private final BigDecimal alpha;
    private final BigDecimal oneMinusAlpha;
    private BigDecimal sumForBootstrap = BigDecimal.ZERO;
    private int observed;
    private BigDecimal ema; // null until bootstrap completes

    public EmaCalculator(int period) {
        if (period <= 0) {
            throw new IllegalArgumentException("period must be positive: " + period);
        }
        this.period = period;
        this.alpha = BigDecimal.valueOf(2)
                .divide(BigDecimal.valueOf(period + 1L), MC);
        this.oneMinusAlpha = BigDecimal.ONE.subtract(alpha, MC);
    }

    /**
     * Update with a new sample. Returns the new EMA value, or {@code null}
     * if still in bootstrap (not yet observed {@code period} samples).
     */
    public BigDecimal update(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        observed++;
        if (observed < period) {
            sumForBootstrap = sumForBootstrap.add(value, MC);
            return null;
        }
        if (observed == period) {
            sumForBootstrap = sumForBootstrap.add(value, MC);
            ema = sumForBootstrap.divide(BigDecimal.valueOf(period), MC);
            return ema;
        }
        // Steady state: ema_t = alpha * value + (1 - alpha) * ema_{t-1}
        ema = alpha.multiply(value, MC).add(oneMinusAlpha.multiply(ema, MC), MC);
        return ema;
    }

    /** Current value, or {@code null} during bootstrap. */
    public BigDecimal value() {
        return ema;
    }

    public boolean isReady() {
        return ema != null;
    }

    public int period() {
        return period;
    }

    public int observed() {
        return observed;
    }

    /** Round to a given scale; useful when comparing with reference data. */
    public static BigDecimal round(BigDecimal v, int scale) {
        return v == null ? null : v.setScale(scale, RoundingMode.HALF_UP);
    }
}
