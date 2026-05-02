package com.levelsweep.execution.stopwatch;

import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.options.OptionSide;
import com.levelsweep.shared.domain.trade.TradeStopTriggered;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure stateless evaluator for the Phase 3 §9 stop trigger:
 *
 * <ul>
 *   <li>§9.1 — CALL: stop fires when the 2-min candle <b>close</b> is below
 *       {@code EMA13}. PUT: stop fires when the close is above {@code EMA13}.
 *   <li>§9.2 — EMA48 exception: when {@code |EMA13 − EMA48| < 0.5 × ATR(14)}
 *       at trigger evaluation time, swap the stop reference from EMA13 to
 *       EMA48. The trade is then held until the close violates EMA48 by the
 *       same direction rule as §9.1.
 * </ul>
 *
 * <p>Returns {@link Optional#empty()} when indicators are unavailable
 * (warm-up): EMA13 / EMA48 / ATR14 must all be present and non-null. This
 * is the fail-closed contract — a missing indicator never silently fires a
 * spurious exit (ADR-0005 §1).
 *
 * <p>The {@link Decision} return value is intentionally minimal — it captures
 * the {@code stopReference} string to fold into a {@link TradeStopTriggered}
 * event, but does not assemble the event itself (the watcher service does,
 * with the bar's tenantId/tradeId/correlationId from the registered stop).
 *
 * <p>Determinism: pure function over (Bar, IndicatorSnapshot,
 * RegisteredStop). Two evaluations with the same inputs produce the same
 * result. No clock reads, no mutable state.
 */
public final class StopTriggerEvaluator {

    /** Threshold multiplier for the §9.2 EMA48 exception: {@code 0.5 × ATR(14)}. */
    static final BigDecimal HALF = new BigDecimal("0.5");

    private StopTriggerEvaluator() {}

    /**
     * Evaluate the stop trigger for one (bar, indicator) pair against a
     * registered stop. Returns {@link Optional#empty()} when:
     *
     * <ul>
     *   <li>The indicator is in warm-up (one of EMA13 / EMA48 / ATR14 is null), OR
     *   <li>The bar's close does not violate the chosen reference (no fire).
     * </ul>
     *
     * @return a {@link Decision} with the stop reference identifier when the
     *     trigger fires, otherwise empty.
     */
    public static Optional<Decision> evaluate(Bar bar, IndicatorSnapshot indicator, OptionSide side) {
        Objects.requireNonNull(bar, "bar");
        Objects.requireNonNull(indicator, "indicator");
        Objects.requireNonNull(side, "side");

        BigDecimal ema13 = indicator.ema13();
        BigDecimal ema48 = indicator.ema48();
        BigDecimal atr14 = indicator.atr14();
        if (ema13 == null || ema48 == null || atr14 == null) {
            return Optional.empty();
        }

        // §9.2: choose EMA48 when |EMA13 - EMA48| < 0.5 × ATR(14). Compare
        // absolute spread to the threshold; tie at "equal to" goes to EMA13
        // (strict inequality per the spec).
        BigDecimal spread = ema13.subtract(ema48).abs();
        BigDecimal threshold = atr14.multiply(HALF).setScale(8, RoundingMode.HALF_UP);
        boolean useEma48 = spread.compareTo(threshold) < 0;

        BigDecimal reference = useEma48 ? ema48 : ema13;
        String referenceLabel = useEma48 ? TradeStopTriggered.STOP_REF_EMA48 : TradeStopTriggered.STOP_REF_EMA13;

        boolean fires =
                switch (side) {
                        // CALL stops out when close is BELOW reference (long-call
                        // premium decay tracks underlying rolling under EMA).
                    case CALL -> bar.close().compareTo(reference) < 0;
                        // PUT stops out when close is ABOVE reference (long-put
                        // premium decay tracks underlying recovering above EMA).
                    case PUT -> bar.close().compareTo(reference) > 0;
                };

        if (!fires) {
            return Optional.empty();
        }
        return Optional.of(new Decision(referenceLabel, bar.close()));
    }

    /**
     * Result of a firing trigger: which reference the watcher should record
     * on the audit row + event ({@code "EMA13"} or {@code "EMA48"}), and the
     * bar close that produced it.
     */
    public record Decision(String stopReference, BigDecimal barClose) {
        public Decision {
            Objects.requireNonNull(stopReference, "stopReference");
            Objects.requireNonNull(barClose, "barClose");
        }
    }
}
