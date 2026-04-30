package com.levelsweep.shared.domain.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TickTest {

    @Test
    void constructsValidTick() {
        Tick t = new Tick("SPY", BigDecimal.valueOf(594.23), 100L, Instant.ofEpochMilli(1714492800000L));
        assertThat(t.symbol()).isEqualTo("SPY");
        assertThat(t.price()).isEqualByComparingTo("594.23");
        assertThat(t.size()).isEqualTo(100L);
    }

    @Test
    void rejectsNullSymbol() {
        assertThatThrownBy(() -> new Tick(null, BigDecimal.ONE, 1L, Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullPrice() {
        assertThatThrownBy(() -> new Tick("SPY", null, 1L, Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNegativePrice() {
        assertThatThrownBy(
                        () -> new Tick("SPY", BigDecimal.valueOf(-1), 1L, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price must be non-negative");
    }

    @Test
    void rejectsNegativeSize() {
        assertThatThrownBy(() -> new Tick("SPY", BigDecimal.ONE, -5L, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size must be non-negative");
    }

    @Test
    void allowsZeroSize() {
        // Polygon sometimes reports odd-lot prints with size 0
        Tick t = new Tick("SPY", BigDecimal.ONE, 0L, Instant.now());
        assertThat(t.size()).isZero();
    }
}
