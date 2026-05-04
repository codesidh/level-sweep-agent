package com.levelsweep.projection.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Output of a Monte Carlo projection run.
 *
 * <p>Percentile shorthand:
 *
 * <ul>
 *   <li>{@code p10} — 10th percentile of final equity ("downside path").</li>
 *   <li>{@code p25} — 25th percentile.</li>
 *   <li>{@code p50} — median.</li>
 *   <li>{@code p75} — 75th percentile.</li>
 *   <li>{@code p90} — 90th percentile ("upside path").</li>
 * </ul>
 *
 * <p>{@code mean} — arithmetic mean of final equity across all simulated paths.
 *
 * <p>{@code ruinProbability} — fraction of simulated paths that either ended
 * with non-positive equity OR touched ≤ 50% of starting equity at any step.
 * The 50% drawdown floor is the Phase 6 ruin definition: positive but deeply
 * drawn-down paths still count as ruined because they would have triggered
 * the deterministic stop-loss / risk-budget halt before getting there.
 *
 * <p>{@code simulationsRun} — actual count of paths simulated, after the
 * engine clamps to {@link ProjectionRequest#MAX_SIMULATIONS}.
 *
 * <p>{@code requestHash} — SHA-256 hash of (tenantId, normalised request),
 * carried on the response so the dashboard can de-duplicate identical reruns.
 */
public record ProjectionResult(
        @JsonProperty("p10") double p10,
        @JsonProperty("p25") double p25,
        @JsonProperty("p50") double p50,
        @JsonProperty("p75") double p75,
        @JsonProperty("p90") double p90,
        @JsonProperty("mean") double mean,
        @JsonProperty("ruinProbability") double ruinProbability,
        @JsonProperty("simulationsRun") int simulationsRun,
        @JsonProperty("requestHash") String requestHash) {}
