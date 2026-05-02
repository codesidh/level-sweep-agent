package com.levelsweep.aiagent.reviewer;

import java.util.Objects;

/**
 * One advisory config-tweak proposal produced by the {@link DailyReviewer}.
 *
 * <p><b>Phase A boundary</b> (architecture-spec §22 #10 + §4.4 + the
 * {@code ai-prompt-management} skill MUST NOT #4): proposals are <em>advisory
 * only</em>. The reviewer never auto-applies. The user (owner) reviews
 * each proposal in the UI and decides. Phase B (gated on legal review) may
 * unlock auto-apply for trusted users — that lives behind a feature flag and
 * is explicitly out of scope for this PR.
 *
 * <p>Default urgency is {@link Urgency#LOW} — the system prompt steers Opus
 * away from hyperbole; high-urgency proposals require multiple matching
 * patterns over the prior 5 days (Phase 5/6 enhancement when calendar-aware
 * lookback lands).
 *
 * @param changeSpec natural-language description of the suggested change
 *                   (e.g. "raise EMA48 stop tolerance from 0.10 to 0.15 ATR")
 * @param rationale  why the reviewer flagged this — anchored in observed
 *                   patterns from {@code priorFiveDays}, never speculation
 * @param urgency    LOW (default) / MEDIUM / HIGH; the reviewer's prompt
 *                   reserves HIGH for repeated-pattern signals across days
 */
public record ConfigProposal(String changeSpec, String rationale, Urgency urgency) {

    /**
     * Urgency tiers. The system prompt instructs Opus to default to LOW and
     * reserve higher tiers for cross-day patterns.
     */
    public enum Urgency {
        LOW,
        MEDIUM,
        HIGH
    }

    public ConfigProposal {
        Objects.requireNonNull(changeSpec, "changeSpec");
        Objects.requireNonNull(rationale, "rationale");
        Objects.requireNonNull(urgency, "urgency");
        if (changeSpec.isBlank()) {
            throw new IllegalArgumentException("changeSpec must not be blank");
        }
        if (rationale.isBlank()) {
            throw new IllegalArgumentException("rationale must not be blank");
        }
    }
}
