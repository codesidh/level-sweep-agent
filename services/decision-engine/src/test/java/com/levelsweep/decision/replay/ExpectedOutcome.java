package com.levelsweep.decision.replay;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Hand-labeled expected outcome of a synthetic session running through the
 * Decision Engine pipeline. Persisted as JSON in
 * {@code services/decision-engine/src/test/resources/replay/<session-name>/expected.json}
 * and asserted byte-for-byte against the actual pipeline output by
 * {@link DecisionReplayHarness}.
 *
 * <p>Per the {@code replay-parity} skill, the expected outcome IS the parity
 * contract — if the pipeline stops emitting the same counts, breakdowns, or
 * first-take coordinates, the harness fails. Tolerances are zero (this is
 * synthetic data, not P&L data); any drift is a bug.
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@code sessionName} — directory name under {@code replay/} (e.g.
 *       {@code session-pdh-sweep-short})
 *   <li>{@code barCount} — total 2-min bars fed
 *   <li>{@code signalEvaluations} — total {@code SignalEvaluator.evaluate()}
 *       calls (one per bar)
 *   <li>{@code signalsTaken} — count of evaluations whose action != SKIP
 *   <li>{@code signalsSkipped} — count of SKIP evaluations (= {@code signalEvaluations - signalsTaken})
 *   <li>{@code skipReasonCounts} — bag of first-reason → count for SKIP evaluations
 *       (e.g. {@code {"sweep:none": 194, "ema_stack_mismatch:LONG_setup_but_13<48<200": 1}}).
 *       The first reason is used as the bucket key because it's the dominant
 *       gate that fired; downstream reasons are diagnostic.
 *   <li>{@code firstTakenLevel} — name of the swept level on the first non-SKIP
 *       evaluation (e.g. {@code "PDH"}), or empty if none taken
 *   <li>{@code firstTakenAt} — evaluatedAt timestamp of the first non-SKIP
 *       evaluation, or empty if none taken
 * </ul>
 */
public record ExpectedOutcome(
        String sessionName,
        int barCount,
        int signalEvaluations,
        int signalsTaken,
        int signalsSkipped,
        Map<String, Integer> skipReasonCounts,
        Optional<String> firstTakenLevel,
        Optional<Instant> firstTakenAt) {

    @JsonCreator
    public ExpectedOutcome(
            @JsonProperty("sessionName") String sessionName,
            @JsonProperty("barCount") int barCount,
            @JsonProperty("signalEvaluations") int signalEvaluations,
            @JsonProperty("signalsTaken") int signalsTaken,
            @JsonProperty("signalsSkipped") int signalsSkipped,
            @JsonProperty("skipReasonCounts") Map<String, Integer> skipReasonCounts,
            @JsonProperty("firstTakenLevel") Optional<String> firstTakenLevel,
            @JsonProperty("firstTakenAt") Optional<Instant> firstTakenAt) {
        this.sessionName = Objects.requireNonNull(sessionName, "sessionName");
        if (barCount < 0) {
            throw new IllegalArgumentException("barCount must be >= 0; got " + barCount);
        }
        if (signalEvaluations != barCount) {
            throw new IllegalArgumentException(
                    "signalEvaluations must equal barCount; got " + signalEvaluations + " vs " + barCount);
        }
        if (signalsTaken < 0 || signalsSkipped < 0) {
            throw new IllegalArgumentException("counts must be >= 0");
        }
        if (signalsTaken + signalsSkipped != signalEvaluations) {
            throw new IllegalArgumentException(
                    "taken + skipped must equal signalEvaluations; got "
                            + signalsTaken + " + " + signalsSkipped + " != " + signalEvaluations);
        }
        this.barCount = barCount;
        this.signalEvaluations = signalEvaluations;
        this.signalsTaken = signalsTaken;
        this.signalsSkipped = signalsSkipped;
        this.skipReasonCounts =
                skipReasonCounts == null ? Map.of() : new LinkedHashMap<>(skipReasonCounts);
        this.firstTakenLevel = firstTakenLevel == null ? Optional.empty() : firstTakenLevel;
        this.firstTakenAt = firstTakenAt == null ? Optional.empty() : firstTakenAt;
    }
}
