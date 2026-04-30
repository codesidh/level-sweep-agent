package com.levelsweep.shared.domain.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LevelsTest {

    @Test
    void constructsValidLevels() {
        Levels l = new Levels(
                "OWNER",
                "SPY",
                LocalDate.of(2026, 4, 30),
                BigDecimal.valueOf(595.0),
                BigDecimal.valueOf(593.0),
                BigDecimal.valueOf(594.5),
                BigDecimal.valueOf(593.5));
        assertThat(l.pdh()).isEqualByComparingTo("595.0");
    }

    @Test
    void rejectsPdlAbovePdh() {
        assertThatThrownBy(() -> new Levels(
                        "OWNER",
                        "SPY",
                        LocalDate.of(2026, 4, 30),
                        BigDecimal.valueOf(593.0),
                        BigDecimal.valueOf(595.0),
                        BigDecimal.valueOf(594.5),
                        BigDecimal.valueOf(593.5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pdl > pdh");
    }

    @Test
    void rejectsPmlAbovePmh() {
        assertThatThrownBy(() -> new Levels(
                        "OWNER",
                        "SPY",
                        LocalDate.of(2026, 4, 30),
                        BigDecimal.valueOf(595.0),
                        BigDecimal.valueOf(593.0),
                        BigDecimal.valueOf(593.5),
                        BigDecimal.valueOf(594.5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pml > pmh");
    }

    @Test
    void rejectsNullTenant() {
        assertThatThrownBy(() -> new Levels(
                        null,
                        "SPY",
                        LocalDate.of(2026, 4, 30),
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE))
                .isInstanceOf(NullPointerException.class);
    }
}
