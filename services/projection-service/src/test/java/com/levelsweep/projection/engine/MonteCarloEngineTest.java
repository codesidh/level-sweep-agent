package com.levelsweep.projection.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.levelsweep.projection.domain.ProjectionRequest;
import com.levelsweep.projection.domain.ProjectionResult;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MonteCarloEngine}. Pure JUnit + AssertJ — no Spring,
 * no Mongo, no Mockito. Verifies:
 *
 * <ul>
 *   <li>Determinism: same seed → identical output across calls.</li>
 *   <li>Trivial sanity: 100% win-rate path → no ruin (ruinProbability = 0.0)
 *       and ascending equity; 0% win-rate path → ruin = 1.0.</li>
 *   <li>Percentile correctness: linear-interpolation matches the NumPy
 *       default for monotone inputs.</li>
 *   <li>Simulations cap: {@link MonteCarloEngine} clamps to
 *       {@link ProjectionRequest#MAX_SIMULATIONS} when invoked directly with
 *       an oversized request.</li>
 * </ul>
 *
 * <p>The engine's hard-cap test invokes {@link MonteCarloEngine#run} with a
 * request that bypasses the {@code @Max(100_000)} controller-level check; the
 * engine clamps internally so it stays safe under direct invocation.
 */
class MonteCarloEngineTest {

    private final MonteCarloEngine engine = new MonteCarloEngine();

    private static ProjectionRequest req(double winRatePct, double lossPct, int sessionsPerWeek, int horizonWeeks) {
        return new ProjectionRequest(
                "OWNER",
                10_000.0, // startingEquity
                winRatePct,
                lossPct,
                sessionsPerWeek,
                horizonWeeks,
                2.0, // positionSizePct (2% of equity per trade)
                1_000, // simulations
                null);
    }

    @Test
    void identicalSeedYieldsIdenticalOutput() {
        ProjectionRequest request = req(55.0, 50.0, 5, 12);
        ProjectionResult a = engine.run(request, 42L);
        ProjectionResult b = engine.run(request, 42L);
        // Doubles are bitwise-equal because the RNG sequence is deterministic
        // for a given seed and the simulation loop is single-threaded.
        assertThat(b.p10()).isEqualTo(a.p10());
        assertThat(b.p25()).isEqualTo(a.p25());
        assertThat(b.p50()).isEqualTo(a.p50());
        assertThat(b.p75()).isEqualTo(a.p75());
        assertThat(b.p90()).isEqualTo(a.p90());
        assertThat(b.mean()).isEqualTo(a.mean());
        assertThat(b.ruinProbability()).isEqualTo(a.ruinProbability());
        assertThat(b.simulationsRun()).isEqualTo(a.simulationsRun());
    }

    @Test
    void differentSeedsYieldDifferentOutput() {
        ProjectionRequest request = req(55.0, 50.0, 5, 12);
        ProjectionResult a = engine.run(request, 42L);
        ProjectionResult b = engine.run(request, 43L);
        // Different seeds → different RNG sequences → different sample paths.
        // At least one summary must differ; usually all do.
        boolean anyDiff = a.p50() != b.p50() || a.mean() != b.mean() || a.ruinProbability() != b.ruinProbability();
        assertThat(anyDiff).isTrue();
    }

    @Test
    void hundredPercentWinRateMeansNoRuinAndGrowingEquity() {
        // Every trial is a win → equity multiplies by (1 + 0.02 * 0.50) = 1.01
        // per step. With 5 sessions/week × 4 weeks = 20 steps, final equity
        // is 10000 * 1.01^20 ≈ 12201.90 on every path.
        ProjectionRequest request = new ProjectionRequest(
                "OWNER", 10_000.0, 100.0, // 100% win
                50.0, 5, 4, 2.0, 500, null);

        ProjectionResult result = engine.run(request, 1L);

        assertThat(result.ruinProbability()).isEqualTo(0.0);
        // Every path is identical → all percentiles equal the deterministic
        // upside path. (1.01)^20 ≈ 1.22019.
        double expected = 10_000.0 * Math.pow(1.01, 20);
        assertThat(result.p10()).isCloseTo(expected, within(0.01));
        assertThat(result.p50()).isCloseTo(expected, within(0.01));
        assertThat(result.p90()).isCloseTo(expected, within(0.01));
        assertThat(result.mean()).isCloseTo(expected, within(0.01));
    }

    @Test
    void zeroPercentWinRateMeansFullRuinForLargeMoves() {
        // Every trial is a loss with positionSize=20%, lossPct=50% → equity
        // multiplies by (1 - 0.20 * 0.50) = 0.90 per step. Over 5×20 = 100
        // steps, equity collapses to 10000 * 0.90^100 ≈ 0.27 — well under
        // the 50% drawdown floor. Every path ruins.
        ProjectionRequest request = new ProjectionRequest(
                "OWNER", 10_000.0, 0.0, // 0% win
                50.0, 5, 20, 20.0, // 20% position size
                500, null);

        ProjectionResult result = engine.run(request, 1L);

        assertThat(result.ruinProbability()).isEqualTo(1.0);
        // Final equity is identical on every path because there's no
        // randomness on a 0% win-rate run.
        double expected = 10_000.0 * Math.pow(0.90, 100);
        assertThat(result.p50()).isCloseTo(expected, within(1e-6));
    }

    @Test
    void simulationsCountIsRespected() {
        ProjectionRequest request = req(55.0, 50.0, 5, 1);
        ProjectionResult result = engine.run(request, 1L);
        assertThat(result.simulationsRun()).isEqualTo(1_000);
    }

    @Test
    void simulationsHardCappedAtMax() {
        // Bypass the controller's @Max(100_000) by constructing the record
        // directly with a doubled count. The engine's internal clamp is the
        // last line of defence against a 1M-sim run on a misconfigured caller.
        ProjectionRequest oversized =
                new ProjectionRequest("OWNER", 10_000.0, 55.0, 50.0, 5, 1, 2.0, 200_000, null); // 2x the cap

        ProjectionResult result = engine.run(oversized, 1L);

        assertThat(result.simulationsRun()).isEqualTo(ProjectionRequest.MAX_SIMULATIONS);
    }

    @Test
    void percentilesAreOrderedForRandomInputs() {
        // For any non-degenerate distribution the percentile ladder must be
        // non-decreasing: p10 ≤ p25 ≤ p50 ≤ p75 ≤ p90.
        ProjectionRequest request = req(55.0, 50.0, 5, 26);
        ProjectionResult result = engine.run(request, 7L);

        assertThat(result.p10()).isLessThanOrEqualTo(result.p25());
        assertThat(result.p25()).isLessThanOrEqualTo(result.p50());
        assertThat(result.p50()).isLessThanOrEqualTo(result.p75());
        assertThat(result.p75()).isLessThanOrEqualTo(result.p90());
    }

    @Test
    void percentileMonotoneCaseMatchesNumpyLinearMethod() {
        // For sorted [1,2,3,4,5,6,7,8,9,10] the NumPy 'linear' method gives:
        //   p10  → 1.9     (rank=0.9, lerp(1,2,0.9))
        //   p25  → 3.25    (rank=2.25, lerp(3,4,0.25))
        //   p50  → 5.5     (rank=4.5, lerp(5,6,0.5))
        //   p75  → 7.75    (rank=6.75, lerp(7,8,0.75))
        //   p90  → 9.1     (rank=8.1, lerp(9,10,0.1))
        double[] sorted = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assertThat(MonteCarloEngine.percentile(sorted, 10)).isCloseTo(1.9, within(1e-9));
        assertThat(MonteCarloEngine.percentile(sorted, 25)).isCloseTo(3.25, within(1e-9));
        assertThat(MonteCarloEngine.percentile(sorted, 50)).isCloseTo(5.5, within(1e-9));
        assertThat(MonteCarloEngine.percentile(sorted, 75)).isCloseTo(7.75, within(1e-9));
        assertThat(MonteCarloEngine.percentile(sorted, 90)).isCloseTo(9.1, within(1e-9));
    }

    @Test
    void percentileSingleElementReturnsThatElement() {
        assertThat(MonteCarloEngine.percentile(new double[] {42.0}, 50)).isEqualTo(42.0);
    }

    @Test
    void percentileEmptyReturnsZero() {
        assertThat(MonteCarloEngine.percentile(new double[] {}, 50)).isEqualTo(0.0);
    }

    @Test
    void ruinProbabilityIsBounded() {
        ProjectionRequest request = req(55.0, 50.0, 5, 26);
        ProjectionResult result = engine.run(request, 99L);
        assertThat(result.ruinProbability()).isBetween(0.0, 1.0);
    }
}
