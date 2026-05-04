package com.levelsweep.projection.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Wire shape and domain record for a Monte Carlo projection request.
 *
 * <p>The Bean Validation annotations enforce input ranges at the controller
 * boundary so a malformed POST returns HTTP 400 before any simulation cost is
 * paid. Numeric ranges:
 *
 * <ul>
 *   <li>{@code startingEquity} — strictly positive, capped at $100M to avoid
 *       absurd inputs.</li>
 *   <li>{@code winRatePct} — percentage in {@code [0.0, 100.0]}.</li>
 *   <li>{@code lossPct} — percentage in {@code [0.0, 100.0]} representing the
 *       expected loss size as a fraction of the position (NOT of equity).
 *       The engine multiplies it by {@code positionSizePct} to get the
 *       per-step loss multiplier.</li>
 *   <li>{@code sessionsPerWeek} — integer in {@code [1, 7]}.</li>
 *   <li>{@code horizonWeeks} — integer in {@code [1, 520]} (10y cap).</li>
 *   <li>{@code positionSizePct} — fraction of equity risked per trade,
 *       {@code (0.0, 100.0]} (percent, NOT a 0-1 ratio — chosen to match the
 *       dashboard's UI control which exposes "1.5%" as 1.5).</li>
 *   <li>{@code simulations} — number of Monte Carlo paths,
 *       {@code [100, 100_000]} per the hard cap in the task brief.</li>
 *   <li>{@code seed} — optional. When absent, the controller derives a
 *       deterministic seed from SHA-256 of {@code (tenantId, normalised
 *       request)} so identical requests from the same tenant produce
 *       identical results across replays.</li>
 * </ul>
 *
 * <p>Identical inputs (including either an explicit seed or the same derived
 * seed) MUST yield identical {@link ProjectionResult} outputs — this contract
 * is asserted by {@code MonteCarloEngineTest}.
 */
public record ProjectionRequest(
        @NotBlank @JsonProperty("tenantId") String tenantId,
        @NotNull
                @DecimalMin(value = "0.01", inclusive = true)
                @DecimalMax(value = "100000000.00", inclusive = true)
                @JsonProperty("startingEquity")
                Double startingEquity,
        @NotNull
                @DecimalMin(value = "0.0", inclusive = true)
                @DecimalMax(value = "100.0", inclusive = true)
                @JsonProperty("winRatePct")
                Double winRatePct,
        @NotNull
                @DecimalMin(value = "0.0", inclusive = true)
                @DecimalMax(value = "100.0", inclusive = true)
                @JsonProperty("lossPct")
                Double lossPct,
        @Min(1) @Max(7) @JsonProperty("sessionsPerWeek") int sessionsPerWeek,
        @Min(1) @Max(520) @JsonProperty("horizonWeeks") int horizonWeeks,
        @NotNull
                @DecimalMin(value = "0.0001", inclusive = true)
                @DecimalMax(value = "100.0", inclusive = true)
                @JsonProperty("positionSizePct")
                Double positionSizePct,
        @Min(100) @Max(100_000) @JsonProperty("simulations") int simulations,
        @JsonProperty("seed") Long seed) {

    /** Hard upper bound on simulations per the Phase 6 task brief. */
    public static final int MAX_SIMULATIONS = 100_000;

    @JsonCreator
    public ProjectionRequest {
        // No-op compact constructor: validation is via Bean Validation
        // annotations at the controller boundary. The numeric range checks
        // above are the canonical contract; duplicating them here would
        // double-count failure modes when the controller's @Valid trips.
    }
}
