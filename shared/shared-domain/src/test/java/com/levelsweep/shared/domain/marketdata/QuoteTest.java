package com.levelsweep.shared.domain.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class QuoteTest {

    @Test
    void midComputedFromBidAsk() {
        Quote q = new Quote("SPY", BigDecimal.valueOf(594.20), 100L, BigDecimal.valueOf(594.30), 100L, Instant.now());
        assertThat(q.mid()).isEqualByComparingTo("594.25");
    }

    @Test
    void rejectsNegativeBid() {
        assertThatThrownBy(() -> new Quote("SPY", BigDecimal.valueOf(-0.01), 1L, BigDecimal.ONE, 1L, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullSymbol() {
        assertThatThrownBy(() -> new Quote(null, BigDecimal.ONE, 1L, BigDecimal.ONE, 1L, Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void allowsZeroSizes() {
        Quote q = new Quote("SPY", BigDecimal.ONE, 0L, BigDecimal.ONE, 0L, Instant.now());
        assertThat(q.bidSize()).isZero();
        assertThat(q.askSize()).isZero();
    }
}
