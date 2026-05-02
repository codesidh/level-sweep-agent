package com.levelsweep.aiagent.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CostCalculator}. Exhaustive matrix over Haiku 4.5,
 * Sonnet 4.6, and Opus 4.7 — both with and without cached input tokens.
 *
 * <p>Assertions use {@code isEqualByComparingTo} on {@link BigDecimal} so we
 * compare value-equality rather than scale-equality. The contract is that the
 * helper returns 4-decimal HALF_UP USD; we verify scale separately on a
 * representative subset.
 */
class CostCalculatorTest {

    // --- Haiku 4.5: $1/MTok in, $5/MTok out -----------------------------------

    @Test
    void haikuOneMillionInputOneMillionOutputCostsSixDollars() {
        BigDecimal cost = CostCalculator.compute("claude-haiku-4-5", 1_000_000, 1_000_000, 0);
        assertThat(cost).isEqualByComparingTo(new BigDecimal("6.0000"));
    }

    @Test
    void haikuTypicalSentinelCallCostsAFewCents() {
        // architecture-spec §4.8 typical Sentinel: ~3000 input / ~200 output
        //   in:  3000 / 1_000_000 × $1 = $0.0030
        //   out: 200  / 1_000_000 × $5 = $0.0010
        //   total = $0.0040
        BigDecimal cost = CostCalculator.compute("claude-haiku-4-5", 3000, 200, 0);
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.0040"));
    }

    @Test
    void haikuWithCachedTokensBillsCacheAtTenPercent() {
        // 2000 uncached input + 1000 cached input + 200 output
        //   uncached: 2000 / 1_000_000 × $1     = $0.0020
        //   cached:   1000 / 1_000_000 × $0.10  = $0.0001
        //   output:   200  / 1_000_000 × $5     = $0.0010
        //   total                                = $0.0031
        BigDecimal cost = CostCalculator.compute("claude-haiku-4-5", 2000, 200, 1000);
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.0031"));
    }

    // --- Sonnet 4.6: $3/MTok in, $15/MTok out ---------------------------------

    @Test
    void sonnetOneMillionInputOneMillionOutputCostsEighteenDollars() {
        BigDecimal cost = CostCalculator.compute("claude-sonnet-4-6", 1_000_000, 1_000_000, 0);
        assertThat(cost).isEqualByComparingTo(new BigDecimal("18.0000"));
    }

    @Test
    void sonnetTypicalNarratorCallMatchesSpec() {
        // architecture-spec §4.8 typical Narrator: ~1500 input / ~150 output
        //   in:  1500 / 1_000_000 × $3  = $0.0045
        //   out: 150  / 1_000_000 × $15 = $0.00225 → rounds HALF_UP to $0.0023 at 4 dp
        //   total                        = $0.0068
        BigDecimal cost = CostCalculator.compute("claude-sonnet-4-6", 1500, 150, 0);
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.0068"));
    }

    @Test
    void sonnetWithCacheReducesCostMaterially() {
        // Heavy cache hit — most input is cached.
        //   uncached: 100  / 1_000_000 × $3        = $0.00030
        //   cached:   2000 / 1_000_000 × $0.30     = $0.00060
        //   output:   500  / 1_000_000 × $15       = $0.0075
        //   total                                   = $0.0084
        BigDecimal cost = CostCalculator.compute("claude-sonnet-4-6", 100, 500, 2000);
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.0084"));
    }

    // --- Opus 4.7: $15/MTok in, $75/MTok out ----------------------------------

    @Test
    void opusOneMillionInputOneMillionOutputCostsNinetyDollars() {
        BigDecimal cost = CostCalculator.compute("claude-opus-4-7", 1_000_000, 1_000_000, 0);
        assertThat(cost).isEqualByComparingTo(new BigDecimal("90.0000"));
    }

    @Test
    void opusTypicalReviewerCallMatchesSpec() {
        // architecture-spec §4.8 typical Reviewer: ~10000 input / ~1500 output
        //   in:  10000 / 1_000_000 × $15 = $0.1500
        //   out: 1500  / 1_000_000 × $75 = $0.1125
        //   total                          = $0.2625
        BigDecimal cost = CostCalculator.compute("claude-opus-4-7", 10_000, 1_500, 0);
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.2625"));
    }

    // --- Edge cases -----------------------------------------------------------

    @Test
    void zeroTokensCostsZero() {
        assertThat(CostCalculator.compute("claude-haiku-4-5", 0, 0, 0)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(CostCalculator.compute("claude-sonnet-4-6", 0, 0, 0)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(CostCalculator.compute("claude-opus-4-7", 0, 0, 0)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void scaleIsAlwaysFourDecimalPlaces() {
        // Pick a number that wouldn't naturally round to 4 dp.
        BigDecimal cost = CostCalculator.compute("claude-haiku-4-5", 1, 1, 0);
        assertThat(cost.scale()).isEqualTo(CostCalculator.SCALE);
    }

    @Test
    void roundingIsHalfUpAtFourDp() {
        // 1500 input × $3/MTok = $0.00450 → rounds to $0.0045 (the trailing 0
        // is the rounding-tie case — HALF_UP rounds 5 away from zero, i.e. up).
        // Use a value that exercises the 5 in the 5th decimal:
        //   55 input × $3/MTok = 55 × 3 / 1_000_000 = 0.000165 → 0.0002 at 4dp HALF_UP
        BigDecimal cost = CostCalculator.compute("claude-sonnet-4-6", 55, 0, 0);
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.0002"));
    }

    @Test
    void veryLargeCountsDoNotOverflow() {
        // 100 million input + 10 million output on Opus — well past any
        // realistic per-call budget, but BigDecimal arithmetic should not
        // overflow.
        BigDecimal cost = CostCalculator.compute("claude-opus-4-7", 100_000_000, 10_000_000, 0);
        // input:  100 × $15  = $1500
        // output: 10  × $75  = $750
        // total              = $2250
        assertThat(cost).isEqualByComparingTo(new BigDecimal("2250.0000"));
    }

    @Test
    void unknownModelThrows() {
        assertThatThrownBy(() -> CostCalculator.compute("claude-3-opus", 100, 100, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown model");
    }

    @Test
    void negativeTokenCountsThrow() {
        assertThatThrownBy(() -> CostCalculator.compute("claude-haiku-4-5", -1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CostCalculator.compute("claude-haiku-4-5", 0, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CostCalculator.compute("claude-haiku-4-5", 0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void knowsModelMatchesPricedSet() {
        assertThat(CostCalculator.knowsModel("claude-haiku-4-5")).isTrue();
        assertThat(CostCalculator.knowsModel("claude-sonnet-4-6")).isTrue();
        assertThat(CostCalculator.knowsModel("claude-opus-4-7")).isTrue();
        assertThat(CostCalculator.knowsModel("claude-3-opus")).isFalse();
    }
}
