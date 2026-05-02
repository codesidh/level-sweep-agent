package com.levelsweep.shared.domain.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TradeTrailRatchetedTest {

    private static final Instant TS = Instant.parse("2026-04-30T15:00:00Z");

    @Test
    void canConstructWithValidValues() {
        TradeTrailRatcheted evt = new TradeTrailRatcheted(
                "OWNER", "t1", TS, new BigDecimal("1.30"), new BigDecimal("0.30"), new BigDecimal("0.25"), "corr-1");
        assertThat(evt.uplPct()).isEqualByComparingTo(new BigDecimal("0.30"));
        assertThat(evt.newFloorPct()).isEqualByComparingTo(new BigDecimal("0.25"));
    }

    @Test
    void rejectsNegativeNbboMid() {
        assertThatThrownBy(() -> new TradeTrailRatcheted(
                        "OWNER", "t1", TS, new BigDecimal("-1"), BigDecimal.ZERO, BigDecimal.ZERO, "corr-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
