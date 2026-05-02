package com.levelsweep.execution.stopwatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import com.levelsweep.shared.domain.options.OptionSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pure POJO truth-table for {@link StopTriggerEvaluator}: full §9.1 + §9.2
 * coverage. No Quarkus, no CDI — just (Bar, IndicatorSnapshot, side) →
 * Optional&lt;Decision&gt;.
 */
class StopTriggerEvaluatorTest {

    private static final String SPY = "SPY";
    private static final Instant T0 = Instant.parse("2026-04-30T13:30:00Z");
    private static final Instant T2 = Instant.parse("2026-04-30T13:32:00Z");

    private static Bar bar(BigDecimal close) {
        BigDecimal high = close.add(new BigDecimal("0.20"));
        BigDecimal low = close.subtract(new BigDecimal("0.20"));
        BigDecimal open = close;
        return new Bar(SPY, Timeframe.TWO_MIN, T0, T2, open, high, low, close, 100L, 50L);
    }

    private static IndicatorSnapshot ind(BigDecimal ema13, BigDecimal ema48, BigDecimal atr14) {
        return new IndicatorSnapshot(SPY, T2, ema13, ema48, BigDecimal.ZERO, atr14);
    }

    // ============== §9.1 — basic CALL trigger ==============

    @Test
    void callFiresWhenCloseBelowEma13_andSpreadWideEnoughForEma13() {
        // EMA13=595, EMA48=590, ATR14=2 → |spread|=5, threshold=0.5*2=1 → spread>=threshold → use EMA13.
        Bar bar = bar(new BigDecimal("594.00"));
        IndicatorSnapshot ind = ind(new BigDecimal("595.00"), new BigDecimal("590.00"), new BigDecimal("2.00"));

        Optional<StopTriggerEvaluator.Decision> result = StopTriggerEvaluator.evaluate(bar, ind, OptionSide.CALL);

        assertThat(result).isPresent();
        assertThat(result.get().stopReference()).isEqualTo("EMA13");
        assertThat(result.get().barClose()).isEqualByComparingTo("594.00");
    }

    @Test
    void callDoesNotFireWhenCloseAtEma13() {
        Bar bar = bar(new BigDecimal("595.00"));
        IndicatorSnapshot ind = ind(new BigDecimal("595.00"), new BigDecimal("590.00"), new BigDecimal("2.00"));

        assertThat(StopTriggerEvaluator.evaluate(bar, ind, OptionSide.CALL)).isEmpty();
    }

    @Test
    void callDoesNotFireWhenCloseAboveEma13() {
        Bar bar = bar(new BigDecimal("596.00"));
        IndicatorSnapshot ind = ind(new BigDecimal("595.00"), new BigDecimal("590.00"), new BigDecimal("2.00"));

        assertThat(StopTriggerEvaluator.evaluate(bar, ind, OptionSide.CALL)).isEmpty();
    }

    // ============== §9.1 — basic PUT trigger ==============

    @Test
    void putFiresWhenCloseAboveEma13_andSpreadWideEnoughForEma13() {
        // PUT — close above EMA13.
        Bar bar = bar(new BigDecimal("596.00"));
        IndicatorSnapshot ind = ind(new BigDecimal("595.00"), new BigDecimal("590.00"), new BigDecimal("2.00"));

        Optional<StopTriggerEvaluator.Decision> result = StopTriggerEvaluator.evaluate(bar, ind, OptionSide.PUT);

        assertThat(result).isPresent();
        assertThat(result.get().stopReference()).isEqualTo("EMA13");
    }

    @Test
    void putDoesNotFireWhenCloseAtEma13() {
        Bar bar = bar(new BigDecimal("595.00"));
        IndicatorSnapshot ind = ind(new BigDecimal("595.00"), new BigDecimal("590.00"), new BigDecimal("2.00"));

        assertThat(StopTriggerEvaluator.evaluate(bar, ind, OptionSide.PUT)).isEmpty();
    }

    @Test
    void putDoesNotFireWhenCloseBelowEma13() {
        Bar bar = bar(new BigDecimal("594.00"));
        IndicatorSnapshot ind = ind(new BigDecimal("595.00"), new BigDecimal("590.00"), new BigDecimal("2.00"));

        assertThat(StopTriggerEvaluator.evaluate(bar, ind, OptionSide.PUT)).isEmpty();
    }

    // ============== §9.2 — EMA48 exception ==============

    @Test
    void callUsesEma48WhenSpreadInsideHalfAtr_callDoesNotFireBecauseCloseAboveEma48() {
        // EMA13=595, EMA48=594, ATR14=4 → |spread|=1, threshold=0.5*4=2 → 1<2 → use EMA48 (594).
        // Close=594.5, EMA48=594 → close > EMA48 → CALL does NOT fire.
        Bar bar = bar(new BigDecimal("594.50"));
        IndicatorSnapshot ind = ind(new BigDecimal("595.00"), new BigDecimal("594.00"), new BigDecimal("4.00"));

        assertThat(StopTriggerEvaluator.evaluate(bar, ind, OptionSide.CALL)).isEmpty();
    }

    @Test
    void callFiresOnEma48WhenSpreadInsideHalfAtr_andCloseBelowEma48() {
        // Same setup but close BELOW EMA48 → CALL fires on EMA48.
        Bar bar = bar(new BigDecimal("593.99"));
        IndicatorSnapshot ind = ind(new BigDecimal("595.00"), new BigDecimal("594.00"), new BigDecimal("4.00"));

        Optional<StopTriggerEvaluator.Decision> result = StopTriggerEvaluator.evaluate(bar, ind, OptionSide.CALL);

        assertThat(result).isPresent();
        assertThat(result.get().stopReference()).isEqualTo("EMA48");
        assertThat(result.get().barClose()).isEqualByComparingTo("593.99");
    }

    @Test
    void putFiresOnEma48WhenSpreadInsideHalfAtr_andCloseAboveEma48() {
        Bar bar = bar(new BigDecimal("594.01"));
        IndicatorSnapshot ind = ind(new BigDecimal("595.00"), new BigDecimal("594.00"), new BigDecimal("4.00"));

        Optional<StopTriggerEvaluator.Decision> result = StopTriggerEvaluator.evaluate(bar, ind, OptionSide.PUT);

        assertThat(result).isPresent();
        assertThat(result.get().stopReference()).isEqualTo("EMA48");
    }

    @Test
    void boundaryAtExactlyHalfAtr_usesEma13_notEma48() {
        // |spread|=2, threshold=0.5*4=2 → tie at "equal" → strict-less rule keeps EMA13.
        // Close < EMA13 (595) but > EMA48 (593): CALL fires on EMA13.
        Bar bar = bar(new BigDecimal("594.00"));
        IndicatorSnapshot ind = ind(new BigDecimal("595.00"), new BigDecimal("593.00"), new BigDecimal("4.00"));

        Optional<StopTriggerEvaluator.Decision> result = StopTriggerEvaluator.evaluate(bar, ind, OptionSide.CALL);

        assertThat(result).isPresent();
        assertThat(result.get().stopReference()).isEqualTo("EMA13");
    }

    // ============== Warm-up: missing indicators ==============

    @Test
    void returnsEmptyWhenEma13NotReady() {
        Bar bar = bar(new BigDecimal("594.00"));
        IndicatorSnapshot ind = ind(null, new BigDecimal("594.00"), new BigDecimal("2.00"));

        assertThat(StopTriggerEvaluator.evaluate(bar, ind, OptionSide.CALL)).isEmpty();
    }

    @Test
    void returnsEmptyWhenEma48NotReady() {
        Bar bar = bar(new BigDecimal("594.00"));
        IndicatorSnapshot ind = ind(new BigDecimal("595.00"), null, new BigDecimal("2.00"));

        assertThat(StopTriggerEvaluator.evaluate(bar, ind, OptionSide.CALL)).isEmpty();
    }

    @Test
    void returnsEmptyWhenAtr14NotReady() {
        Bar bar = bar(new BigDecimal("594.00"));
        IndicatorSnapshot ind = ind(new BigDecimal("595.00"), new BigDecimal("594.00"), null);

        assertThat(StopTriggerEvaluator.evaluate(bar, ind, OptionSide.CALL)).isEmpty();
    }
}
