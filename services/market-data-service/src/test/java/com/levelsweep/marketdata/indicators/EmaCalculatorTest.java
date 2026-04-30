package com.levelsweep.marketdata.indicators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class EmaCalculatorTest {

    @Test
    void rejectsNonPositivePeriod() {
        assertThatThrownBy(() -> new EmaCalculator(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmaCalculator(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnsNullDuringBootstrap() {
        EmaCalculator e = new EmaCalculator(3);
        assertThat(e.update(BigDecimal.valueOf(100))).isNull();
        assertThat(e.update(BigDecimal.valueOf(101))).isNull();
        assertThat(e.update(BigDecimal.valueOf(102))).isNotNull(); // 3rd sample completes bootstrap
    }

    @Test
    void bootstrapValueIsSimpleAverage() {
        EmaCalculator e = new EmaCalculator(3);
        e.update(BigDecimal.valueOf(100));
        e.update(BigDecimal.valueOf(110));
        BigDecimal result = e.update(BigDecimal.valueOf(120));
        assertThat(result).isEqualByComparingTo("110.0"); // (100+110+120)/3
    }

    @Test
    void steadyStateApproximatesEma() {
        // Constant input: EMA stays at that value
        EmaCalculator e = new EmaCalculator(3);
        for (int i = 0; i < 100; i++) {
            e.update(BigDecimal.valueOf(50));
        }
        assertThat(e.value()).isEqualByComparingTo("50.0");
    }

    @Test
    void respondsFasterWithSmallerPeriod() {
        // Larger period → smoother → slower response. Compare 10-period vs 3-period
        EmaCalculator slow = new EmaCalculator(10);
        EmaCalculator fast = new EmaCalculator(3);
        for (int i = 0; i < 50; i++) {
            slow.update(BigDecimal.valueOf(100));
            fast.update(BigDecimal.valueOf(100));
        }
        // Bump price up; smaller period should swing closer to new value
        for (int i = 0; i < 5; i++) {
            slow.update(BigDecimal.valueOf(200));
            fast.update(BigDecimal.valueOf(200));
        }
        assertThat(fast.value().doubleValue()).isGreaterThan(slow.value().doubleValue());
    }

    @Test
    void rejectsNullInput() {
        EmaCalculator e = new EmaCalculator(3);
        assertThatThrownBy(() -> e.update(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readyOnlyAfterBootstrap() {
        EmaCalculator e = new EmaCalculator(3);
        assertThat(e.isReady()).isFalse();
        e.update(BigDecimal.ONE);
        e.update(BigDecimal.ONE);
        assertThat(e.isReady()).isFalse();
        e.update(BigDecimal.ONE);
        assertThat(e.isReady()).isTrue();
    }

    @Test
    void deterministicAcrossRuns() {
        BigDecimal[] inputs = {
            BigDecimal.valueOf(100),
            BigDecimal.valueOf(102),
            BigDecimal.valueOf(98),
            BigDecimal.valueOf(101),
            BigDecimal.valueOf(99),
            BigDecimal.valueOf(103),
            BigDecimal.valueOf(105),
        };
        EmaCalculator a = new EmaCalculator(3);
        EmaCalculator b = new EmaCalculator(3);
        for (BigDecimal v : inputs) {
            a.update(v);
            b.update(v);
        }
        assertThat(a.value()).isEqualByComparingTo(b.value());
    }

    @Test
    void roundExposedForDownstream() {
        BigDecimal v = BigDecimal.valueOf(594.123456);
        assertThat(EmaCalculator.round(v, 2)).isEqualByComparingTo("594.12");
        assertThat(EmaCalculator.round(null, 2)).isNull();
    }
}
