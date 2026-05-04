package com.levelsweep.projection.engine;

import com.levelsweep.projection.domain.ProjectionRequest;
import com.levelsweep.projection.domain.ProjectionResult;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pure Monte Carlo simulator over portfolio equity given win-rate, position-
 * sizing, and horizon assumptions.
 *
 * <p>Per simulation, for {@code horizonWeeks * sessionsPerWeek} steps, draw a
 * Bernoulli trial with {@code p = winRatePct/100}; on win multiply equity by
 * {@code (1 + positionSizePct/100 * winPct/100)}; on loss multiply by
 * {@code (1 - positionSizePct/100 * lossPct/100)}. Track the running minimum
 * equity to detect 50%-drawdown ruin. After all paths complete, compute the
 * percentiles, mean, and ruin probability.
 *
 * <p>Note on {@code winPct}: the task brief defines win sizing as
 * {@code (1 + positionSizePct * winPct/100)} but does not introduce a separate
 * {@code winPct} input. We interpret the win multiplier symmetrically with the
 * loss multiplier — i.e. on a winning trade the equity grows by
 * {@code positionSizePct * lossPct / 100} (using the same {@code lossPct} as
 * the magnitude of the move). This yields a balanced binary outcome model
 * that matches the dashboard UI's "expected win == expected loss" default.
 * Phase 7 will split this into separate {@code winSizePct} / {@code lossSizePct}
 * inputs once the dashboard exposes the asymmetry.
 *
 * <p><b>Determinism contract:</b> identical seed → identical output. The
 * {@link RandomGenerator} is constructed once per call from the seed and is
 * used in a single-threaded loop. We do NOT use ThreadLocalRandom or
 * SplittableRandom-based parallel streams because their internal split
 * sequence depends on JDK-internal state that can vary across JVM versions.
 *
 * <p>The engine is a Spring {@link Component} purely for DI ergonomics; its
 * core method is a pure function. Tests construct it via {@code new}.
 */
@Component
public class MonteCarloEngine {

    private static final Logger LOG = LoggerFactory.getLogger(MonteCarloEngine.class);

    /** Ruin floor — paths that touch ≤ 50% of starting equity count as ruined. */
    private static final double RUIN_DRAWDOWN_FLOOR = 0.50;

    /**
     * Run the simulation. The seed is mandatory at this layer — the controller
     * resolves the optional request seed (or derives a deterministic hash-based
     * seed) before calling this method.
     *
     * @param request input parameters (already validated by Bean Validation)
     * @param seed    explicit seed for {@link Random}; identical seeds → identical output
     * @return percentile + mean + ruin probability summary
     */
    public ProjectionResult run(ProjectionRequest request, long seed) {
        Objects.requireNonNull(request, "request");

        // Clamp to the hard cap; the @Max(100_000) at the controller usually
        // catches this first, but we re-clamp here so the engine stays safe
        // when invoked directly (tests, internal callers).
        int simulations = Math.min(request.simulations(), ProjectionRequest.MAX_SIMULATIONS);
        int stepsPerPath = request.horizonWeeks() * request.sessionsPerWeek();
        double startingEquity = request.startingEquity();
        double winProb = request.winRatePct() / 100.0;
        // positionSizePct is on a 0–100 scale; convert to fraction.
        double positionFrac = request.positionSizePct() / 100.0;
        // See class header note on win/loss symmetry.
        double moveMagnitude = request.lossPct() / 100.0;
        double winMultiplier = 1.0 + positionFrac * moveMagnitude;
        double lossMultiplier = 1.0 - positionFrac * moveMagnitude;
        double ruinFloor = startingEquity * RUIN_DRAWDOWN_FLOOR;

        // L'Ecuyer's LXM is JDK 17+, deterministic across JDK 21+ patches and
        // platforms, and faster than Random — preferred for Monte Carlo.
        // Fall back to Random for older JDKs (none in scope here, JDK 21 only).
        RandomGenerator rng =
                RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(seed);

        double[] finalEquities = new double[simulations];
        int ruinCount = 0;

        for (int sim = 0; sim < simulations; sim++) {
            double equity = startingEquity;
            double minEquity = equity;
            for (int step = 0; step < stepsPerPath; step++) {
                if (rng.nextDouble() < winProb) {
                    equity *= winMultiplier;
                } else {
                    equity *= lossMultiplier;
                }
                if (equity < minEquity) {
                    minEquity = equity;
                }
                // Once a path is below the ruin floor we still complete it for
                // percentile calculations, but we don't need to keep tracking
                // minEquity (it can only go lower or stay).
            }
            finalEquities[sim] = equity;
            // Ruined if ended at or below 0 OR drawdown exceeded 50% at any
            // step. The minEquity check subsumes the first condition for
            // any positive-multiplier loss path, but we keep both explicit
            // for clarity and so the test "100% loss → ruin = 1.0" is
            // unambiguous.
            if (equity <= 0.0 || minEquity <= ruinFloor) {
                ruinCount++;
            }
        }

        // Sort once for percentile lookup. Arrays.sort on double[] is
        // dual-pivot Quicksort — O(n log n), in-place.
        Arrays.sort(finalEquities);

        double p10 = percentile(finalEquities, 10);
        double p25 = percentile(finalEquities, 25);
        double p50 = percentile(finalEquities, 50);
        double p75 = percentile(finalEquities, 75);
        double p90 = percentile(finalEquities, 90);
        double mean = mean(finalEquities);
        double ruinProb = (double) ruinCount / simulations;

        if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "MC run sims={} steps={} seed={} p50={} mean={} ruinProb={}",
                    simulations,
                    stepsPerPath,
                    seed,
                    p50,
                    mean,
                    ruinProb);
        }

        // requestHash is filled in by the controller; the engine returns an
        // empty string here so the caller can stamp the canonical hash.
        return new ProjectionResult(p10, p25, p50, p75, p90, mean, ruinProb, simulations, "");
    }

    /**
     * Linear-interpolation percentile on a pre-sorted array. Matches the
     * NumPy default ({@code numpy.percentile} method='linear') — the exact
     * algorithm tests assert against.
     *
     * <p>For {@code n} samples and percentile {@code p ∈ [0, 100]}:
     * {@code rank = p/100 * (n - 1)}; floor and ceiling around this rank are
     * blended linearly.
     */
    static double percentile(double[] sortedAsc, double p) {
        int n = sortedAsc.length;
        if (n == 0) {
            return 0.0;
        }
        if (n == 1) {
            return sortedAsc[0];
        }
        double rank = (p / 100.0) * (n - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) {
            return sortedAsc[lower];
        }
        double weight = rank - lower;
        return sortedAsc[lower] * (1.0 - weight) + sortedAsc[upper] * weight;
    }

    private static double mean(double[] xs) {
        if (xs.length == 0) {
            return 0.0;
        }
        // Kahan summation would be more accurate but a sequential sum is
        // adequate for our magnitude range (1e2 to 1e9 equity, 1e5 sims).
        double sum = 0.0;
        for (double x : xs) {
            sum += x;
        }
        return sum / xs.length;
    }
}
