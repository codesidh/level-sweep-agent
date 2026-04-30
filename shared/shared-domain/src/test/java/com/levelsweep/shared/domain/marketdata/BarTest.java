package com.levelsweep.shared.domain.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BarTest {

    private static final Instant T0 = Instant.parse("2026-04-30T13:30:00Z");
    private static final Instant T1 = Instant.parse("2026-04-30T13:31:00Z");

    @Test
    void constructsValidBar() {
        Bar bar = new Bar(
                "SPY",
                Timeframe.ONE_MIN,
                T0,
                T1,
                BigDecimal.valueOf(594.10),
                BigDecimal.valueOf(594.30),
                BigDecimal.valueOf(594.05),
                BigDecimal.valueOf(594.25),
                10_000L,
                42L);
        assertThat(bar.symbol()).isEqualTo("SPY");
        assertThat(bar.volume()).isEqualTo(10_000L);
        assertThat(bar.ticks()).isEqualTo(42L);
    }

    @Test
    void rejectsCloseTimeNotAfterOpenTime() {
        assertThatThrownBy(() -> new Bar(
                        "SPY",
                        Timeframe.ONE_MIN,
                        T0,
                        T0,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        0L,
                        0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("closeTime must be after openTime");
    }

    @Test
    void rejectsLowAboveHigh() {
        assertThatThrownBy(() -> new Bar(
                        "SPY",
                        Timeframe.ONE_MIN,
                        T0,
                        T1,
                        BigDecimal.valueOf(100),
                        BigDecimal.valueOf(99),
                        BigDecimal.valueOf(101),
                        BigDecimal.valueOf(100),
                        0L,
                        0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("low > high");
    }

    @Test
    void rejectsLowAboveOpen() {
        assertThatThrownBy(() -> new Bar(
                        "SPY",
                        Timeframe.ONE_MIN,
                        T0,
                        T1,
                        BigDecimal.valueOf(100),
                        BigDecimal.valueOf(105),
                        BigDecimal.valueOf(101),
                        BigDecimal.valueOf(102),
                        0L,
                        0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("envelope");
    }

    @Test
    void rejectsHighBelowClose() {
        assertThatThrownBy(() -> new Bar(
                        "SPY",
                        Timeframe.ONE_MIN,
                        T0,
                        T1,
                        BigDecimal.valueOf(100),
                        BigDecimal.valueOf(101),
                        BigDecimal.valueOf(99),
                        BigDecimal.valueOf(102),
                        0L,
                        0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("envelope");
    }

    @Test
    void rejectsNegativeVolume() {
        assertThatThrownBy(() -> new Bar(
                        "SPY",
                        Timeframe.ONE_MIN,
                        T0,
                        T1,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        -1L,
                        0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsZeroVolumeAndTicks() {
        // Edge case: a bar where every tick had size 0 (odd-lot prints). Volume = 0 valid.
        Bar bar = new Bar(
                "SPY",
                Timeframe.ONE_MIN,
                T0,
                T1,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                0L,
                0L);
        assertThat(bar.volume()).isZero();
    }
}
