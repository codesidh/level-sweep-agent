package com.levelsweep.shared.domain.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TradeTrailBreachedTest {

    private static final Instant TS = Instant.parse("2026-04-30T15:00:00Z");

    @Test
    void canConstructWithValidValues() {
        TradeTrailBreached evt = new TradeTrailBreached(
                "OWNER", "t1", "SPY260430C00595000", TS, new BigDecimal("1.35"), new BigDecimal("0.35"), "corr-1");
        assertThat(evt.exitFloorPct()).isEqualByComparingTo(new BigDecimal("0.35"));
        assertThat(evt.contractSymbol()).isEqualTo("SPY260430C00595000");
    }

    @Test
    void rejectsBlankTenant() {
        assertThatThrownBy(() -> new TradeTrailBreached(
                        "", "t1", "SPY260430C00595000", TS, new BigDecimal("1.35"), new BigDecimal("0.35"), "corr-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
