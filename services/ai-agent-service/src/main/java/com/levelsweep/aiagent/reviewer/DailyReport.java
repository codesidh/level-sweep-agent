package com.levelsweep.aiagent.reviewer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Persistent record produced by the {@link DailyReviewer} at 16:30 ET each
 * trading day (architecture-spec §4.3.4).
 *
 * <p>Stored in {@code journal.daily_reports} (architecture-spec §13.2 row).
 * Joins back to the full prompt body in the cold-blob {@code ai_prompts}
 * collection via {@link #promptHash} so a regulator-style audit can replay the
 * exact inputs that produced any given report.
 *
 * <p>{@code outcome} captures the lifecycle disposition — most rows are
 * {@code COMPLETED}, but a {@code SKIPPED_COST_CAP} row is written for audit
 * consistency when the cost-cap pre-check shorts the call.
 *
 * <p><b>Phase A boundary</b> (architecture-spec §22 #10 + §4.4): {@link #proposals}
 * is empty in Phase A. The review still produces a free-text suggestion in
 * its narrative if one is warranted, but the structured proposals list
 * unlocks only in Phase B once the user-approval flow lands. The reviewer
 * code path constructs an empty list in Phase A regardless of what the model
 * returns.
 *
 * @param tenantId        multi-tenant scope (architecture-spec §22 #4)
 * @param sessionDate     ET local date of the session being reviewed
 * @param summary         multi-paragraph factual summary of the session
 * @param anomalies       short bullet-style entries for things that diverged
 *                        from the prior 5 days
 * @param proposals       advisory config-tweak proposals (Phase A: always empty)
 * @param outcome         disposition of this review run
 * @param generatedAt     wall-clock instant when the reviewer produced this row
 * @param modelUsed       Anthropic model id ({@code claude-opus-4-7} for Phase 4)
 * @param promptHash      SHA-256 of the canonical request — see {@code PromptHasher}
 * @param totalTokensUsed sum of input + output tokens reported by Anthropic
 * @param costUsd         post-call reconciled USD cost
 */
public record DailyReport(
        String tenantId,
        LocalDate sessionDate,
        String summary,
        List<String> anomalies,
        List<ConfigProposal> proposals,
        Outcome outcome,
        Instant generatedAt,
        String modelUsed,
        String promptHash,
        long totalTokensUsed,
        BigDecimal costUsd) {

    /**
     * Lifecycle outcome of one reviewer run. Captured on the persisted row so
     * the audit trail explains why a session may not have a substantive
     * report (architecture-spec §4.10 reproducibility requirement).
     *
     * <ul>
     *   <li>{@code COMPLETED} — Opus call succeeded, summary stored.</li>
     *   <li>{@code SKIPPED_COST_CAP} — pre-flight cost cap blocked the call;
     *       the row is a stub so dashboards / replays know the reviewer
     *       attempted but skipped.</li>
     *   <li>{@code FAILED} — Anthropic call returned a non-Success variant
     *       (rate-limit, transport, etc.). Single attempt only — tomorrow's
     *       run picks up. See {@link DailyReviewer} retry policy.</li>
     * </ul>
     */
    public enum Outcome {
        COMPLETED,
        SKIPPED_COST_CAP,
        FAILED
    }

    public DailyReport {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sessionDate, "sessionDate");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(anomalies, "anomalies");
        Objects.requireNonNull(proposals, "proposals");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(modelUsed, "modelUsed");
        Objects.requireNonNull(promptHash, "promptHash");
        Objects.requireNonNull(costUsd, "costUsd");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (modelUsed.isBlank()) {
            throw new IllegalArgumentException("modelUsed must not be blank");
        }
        if (promptHash.isBlank()) {
            throw new IllegalArgumentException("promptHash must not be blank");
        }
        if (totalTokensUsed < 0) {
            throw new IllegalArgumentException("totalTokensUsed must be non-negative");
        }
        if (costUsd.signum() < 0) {
            throw new IllegalArgumentException("costUsd must be non-negative");
        }
        // Defensive copies — record fields are exposed by reference; downstream
        // mutation would corrupt the audit trail.
        anomalies = List.copyOf(anomalies);
        proposals = List.copyOf(proposals);
    }
}
