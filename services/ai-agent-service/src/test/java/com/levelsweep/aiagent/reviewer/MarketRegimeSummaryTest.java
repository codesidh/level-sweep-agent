package com.levelsweep.aiagent.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Validation tests for {@link MarketRegimeSummary}. Phase 4 ships the type
 * with no live producer; these tests guarantee the contract for Phase 5/6.
 */
class MarketRegimeSummaryTest {

    @Test
    void buildsWithAllFieldsPresent() {
        MarketRegimeSummary r = new MarketRegimeSummary(
                new BigDecimal("16.50"),
                new BigDecimal("17.20"),
                new BigDecimal("0.70"),
                MarketRegimeSummary.SpxTrend.UP,
                new BigDecimal("1.45"),
                Optional.of("FOMC day"));

        assertThat(r.vixOpen()).isEqualByComparingTo("16.50");
        assertThat(r.vixClose()).isEqualByComparingTo("17.20");
        assertThat(r.vixDelta()).isEqualByComparingTo("0.70");
        assertThat(r.spxTrend()).isEqualTo(MarketRegimeSummary.SpxTrend.UP);
        assertThat(r.breadthRatio()).isEqualByComparingTo("1.45");
        assertThat(r.notes()).contains("FOMC day");
    }

    @Test
    void rejectsNullVixOpen() {
        assertThatThrownBy(() -> new MarketRegimeSummary(
                        null,
                        new BigDecimal("17.20"),
                        new BigDecimal("0.70"),
                        MarketRegimeSummary.SpxTrend.UP,
                        new BigDecimal("1.45"),
                        Optional.empty()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("vixOpen");
    }

    @Test
    void rejectsNullSpxTrend() {
        assertThatThrownBy(() -> new MarketRegimeSummary(
                        new BigDecimal("16.50"),
                        new BigDecimal("17.20"),
                        new BigDecimal("0.70"),
                        null,
                        new BigDecimal("1.45"),
                        Optional.empty()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("spxTrend");
    }

    @Test
    void rejectsNullNotesOptional() {
        // Optional.empty() is allowed; null is not.
        assertThatThrownBy(() -> new MarketRegimeSummary(
                        new BigDecimal("16.50"),
                        new BigDecimal("17.20"),
                        new BigDecimal("0.70"),
                        MarketRegimeSummary.SpxTrend.FLAT,
                        new BigDecimal("1.45"),
                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("notes");
    }

    @Test
    void allTrendValuesAccepted() {
        for (MarketRegimeSummary.SpxTrend trend : MarketRegimeSummary.SpxTrend.values()) {
            MarketRegimeSummary r = new MarketRegimeSummary(
                    new BigDecimal("16"),
                    new BigDecimal("16"),
                    BigDecimal.ZERO,
                    trend,
                    BigDecimal.ONE,
                    Optional.empty());
            assertThat(r.spxTrend()).isEqualTo(trend);
        }
    }
}
