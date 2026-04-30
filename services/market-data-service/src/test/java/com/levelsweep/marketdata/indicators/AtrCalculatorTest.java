package com.levelsweep.marketdata.indicators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AtrCalculatorTest {

    private static Bar dailyBar(double open, double high, double low, double close, int dayOffset) {
        Instant openTime = Instant.parse("2026-04-01T04:00:00Z").plusSeconds(dayOffset * 86400L);
        Instant closeTime = openTime.plusSeconds(86400L);
        return new Bar(
                "SPY",
                Timeframe.DAILY,
                openTime,
                closeTime,
                BigDecimal.valueOf(open),
                BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(close),
                1_000_000L,
                100_000L);
    }

    @Test
    void rejectsPeriodOfOneOrLess() {
        assertThatThrownBy(() -> new AtrCalculator(1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AtrCalculator(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnsNullDuringBootstrap() {
        AtrCalculator a = new AtrCalculator(3);
        assertThat(a.update(dailyBar(100, 101, 99, 100, 0))).isNull();
        assertThat(a.update(dailyBar(100, 102, 98, 101, 1))).isNull();
        assertThat(a.update(dailyBar(101, 103, 100, 102, 2))).isNotNull(); // bootstrap completes
    }

    @Test
    void bootstrapIsSimpleAverageOfTrueRanges() {
        AtrCalculator a = new AtrCalculator(3);
        // bar 0: TR = high - low = 101 - 99 = 2 (no prev close)
        a.update(dailyBar(100, 101, 99, 100, 0));
        // bar 1: TR = max(102-98=4, |102-100|=2, |98-100|=2) = 4
        a.update(dailyBar(100, 102, 98, 101, 1));
        // bar 2: TR = max(103-100=3, |103-101|=2, |100-101|=1) = 3
        BigDecimal atr = a.update(dailyBar(101, 103, 100, 102, 2));
        // Bootstrap: (2+4+3)/3 = 3
        assertThat(atr).isEqualByComparingTo("3.0");
    }

    @Test
    void wilderSmoothingApplied() {
        AtrCalculator a = new AtrCalculator(3);
        // Bootstrap with 3 bars; ATR = 3 (from previous test logic)
        a.update(dailyBar(100, 101, 99, 100, 0));
        a.update(dailyBar(100, 102, 98, 101, 1));
        BigDecimal bootstrapAtr = a.update(dailyBar(101, 103, 100, 102, 2));
        assertThat(bootstrapAtr).isEqualByComparingTo("3.0");

        // bar 3: TR = max(105-101=4, |105-102|=3, |101-102|=1) = 4
        // Wilder: ATR_new = (3 * 2 + 4) / 3 = 10/3 = 3.333...
        BigDecimal next = a.update(dailyBar(102, 105, 101, 104, 3));
        assertThat(next.setScale(4, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("3.3333");
    }

    @Test
    void trueRangeWithoutPrevCloseIsHighMinusLow() {
        BigDecimal tr = AtrCalculator.trueRange(BigDecimal.valueOf(105), BigDecimal.valueOf(100), null);
        assertThat(tr).isEqualByComparingTo("5");
    }

    @Test
    void trueRangeChoosesMaxOfThreeWhenPrevCloseExists() {
        // h-l = 5, |h-pc| = 8, |l-pc| = 3 → max = 8
        BigDecimal tr =
                AtrCalculator.trueRange(BigDecimal.valueOf(105), BigDecimal.valueOf(100), BigDecimal.valueOf(97));
        assertThat(tr).isEqualByComparingTo("8");
    }

    @Test
    void rejectsNullBar() {
        AtrCalculator a = new AtrCalculator(3);
        assertThatThrownBy(() -> a.update(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deterministicAcrossRuns() {
        AtrCalculator a = new AtrCalculator(5);
        AtrCalculator b = new AtrCalculator(5);
        for (int i = 0; i < 30; i++) {
            Bar bar = dailyBar(100 + i, 102 + i, 98 + i, 101 + i, i);
            a.update(bar);
            b.update(bar);
        }
        assertThat(a.value()).isEqualByComparingTo(b.value());
    }

    @Test
    void readyAfterBootstrap() {
        AtrCalculator a = new AtrCalculator(3);
        assertThat(a.isReady()).isFalse();
        for (int i = 0; i < 3; i++) {
            a.update(dailyBar(100 + i, 102 + i, 98 + i, 101 + i, i));
        }
        assertThat(a.isReady()).isTrue();
    }
}
