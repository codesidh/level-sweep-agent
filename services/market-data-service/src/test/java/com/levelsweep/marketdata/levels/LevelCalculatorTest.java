package com.levelsweep.marketdata.levels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Levels;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class LevelCalculatorTest {

    private static Bar bar(double open, double high, double low, double close, long minuteOffset) {
        Instant openTime = Instant.parse("2026-04-29T13:30:00Z").plusSeconds(minuteOffset * 60);
        Instant closeTime = openTime.plusSeconds(60);
        return new Bar(
                "SPY",
                Timeframe.ONE_MIN,
                openTime,
                closeTime,
                BigDecimal.valueOf(open),
                BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(close),
                100L,
                1L);
    }

    @Test
    void computesLevelsFromBars() {
        List<Bar> rth = List.of(bar(594.0, 595.0, 593.5, 594.5, 0), bar(594.5, 595.2, 594.0, 594.8, 1));
        List<Bar> overnight = List.of(bar(593.0, 594.5, 592.0, 593.5, 0), bar(593.5, 594.0, 593.0, 593.8, 1));

        Levels levels = LevelCalculator.compute("OWNER", "SPY", LocalDate.of(2026, 4, 30), rth, overnight);

        assertThat(levels.tenantId()).isEqualTo("OWNER");
        assertThat(levels.symbol()).isEqualTo("SPY");
        assertThat(levels.sessionDate()).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(levels.pdh()).isEqualByComparingTo("595.2");
        assertThat(levels.pdl()).isEqualByComparingTo("593.5");
        assertThat(levels.pmh()).isEqualByComparingTo("594.5");
        assertThat(levels.pml()).isEqualByComparingTo("592.0");
    }

    @Test
    void singleBarPerWindow() {
        List<Bar> rth = List.of(bar(594.0, 594.5, 593.5, 594.2, 0));
        List<Bar> overnight = List.of(bar(593.0, 593.8, 592.5, 593.5, 0));

        Levels levels = LevelCalculator.compute("OWNER", "SPY", LocalDate.of(2026, 4, 30), rth, overnight);

        assertThat(levels.pdh()).isEqualByComparingTo("594.5");
        assertThat(levels.pdl()).isEqualByComparingTo("593.5");
    }

    @Test
    void rejectsEmptyRth() {
        List<Bar> overnight = List.of(bar(593.0, 593.8, 592.5, 593.5, 0));
        assertThatThrownBy(
                        () -> LevelCalculator.compute("OWNER", "SPY", LocalDate.of(2026, 4, 30), List.of(), overnight))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rthBars");
    }

    @Test
    void rejectsEmptyOvernight() {
        List<Bar> rth = List.of(bar(594.0, 594.5, 593.5, 594.2, 0));
        assertThatThrownBy(() -> LevelCalculator.compute("OWNER", "SPY", LocalDate.of(2026, 4, 30), rth, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overnightBars");
    }

    @Test
    void rejectsBarsWithWrongSymbol() {
        Bar foreignBar = new Bar(
                "QQQ",
                Timeframe.ONE_MIN,
                Instant.parse("2026-04-29T13:30:00Z"),
                Instant.parse("2026-04-29T13:31:00Z"),
                BigDecimal.valueOf(500.0),
                BigDecimal.valueOf(500.5),
                BigDecimal.valueOf(499.8),
                BigDecimal.valueOf(500.2),
                100L,
                1L);
        List<Bar> rth = List.of(foreignBar);
        List<Bar> overnight = List.of(bar(593.0, 593.8, 592.5, 593.5, 0));
        assertThatThrownBy(() -> LevelCalculator.compute("OWNER", "SPY", LocalDate.of(2026, 4, 30), rth, overnight))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol mismatch");
    }

    @Test
    void rejectsDailyBars() {
        Bar dailyBar = new Bar(
                "SPY",
                Timeframe.DAILY,
                Instant.parse("2026-04-29T04:00:00Z"),
                Instant.parse("2026-04-30T04:00:00Z"),
                BigDecimal.valueOf(594.0),
                BigDecimal.valueOf(595.0),
                BigDecimal.valueOf(593.5),
                BigDecimal.valueOf(594.2),
                1_000_000L,
                100_000L);
        List<Bar> rth = List.of(dailyBar);
        List<Bar> overnight = List.of(bar(593.0, 593.8, 592.5, 593.5, 0));
        assertThatThrownBy(() -> LevelCalculator.compute("OWNER", "SPY", LocalDate.of(2026, 4, 30), rth, overnight))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intraday");
    }

    @Test
    void rthOnlyConvenienceReturnsHighLow() {
        List<Bar> rth = List.of(bar(594.0, 595.0, 593.5, 594.5, 0), bar(594.5, 595.2, 594.0, 594.8, 1));
        var rthLevels = LevelCalculator.computeRth("SPY", rth).orElseThrow();
        assertThat(rthLevels.pdh()).isEqualByComparingTo("595.2");
        assertThat(rthLevels.pdl()).isEqualByComparingTo("593.5");
    }

    @Test
    void rthOnlyOnEmptyReturnsEmpty() {
        assertThat(LevelCalculator.computeRth("SPY", List.of())).isEmpty();
    }

    @Test
    void deterministicAcrossInputOrders() {
        // Permutation of input bars must yield the same levels (order independence).
        List<Bar> a = List.of(bar(594.0, 595.0, 593.5, 594.5, 0), bar(594.5, 595.2, 594.0, 594.8, 1));
        List<Bar> b = List.of(bar(594.5, 595.2, 594.0, 594.8, 1), bar(594.0, 595.0, 593.5, 594.5, 0));
        var first = LevelCalculator.computeRth("SPY", a).orElseThrow();
        var second = LevelCalculator.computeRth("SPY", b).orElseThrow();
        assertThat(first).isEqualTo(second);
    }
}
