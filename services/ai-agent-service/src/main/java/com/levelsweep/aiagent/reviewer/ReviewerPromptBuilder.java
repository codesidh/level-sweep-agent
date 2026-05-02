package com.levelsweep.aiagent.reviewer;

import com.levelsweep.aiagent.narrator.TradeNarrative;
import java.util.Objects;

/**
 * Pure helper that turns a {@link ReviewRequest} into the system prompt + user
 * message for the Anthropic Messages API. Same input → byte-identical output
 * (architecture-spec Principle #2 / replay parity, ADR-0006 §6).
 *
 * <p><b>Determinism contract</b>: the produced strings are the canonical input
 * to {@code com.levelsweep.aiagent.audit.PromptHasher}, so the prompt hash is
 * stable across runs of the reviewer over the same aggregated inputs. Any
 * change to a section template body is a replay-parity-breaking change — bump
 * the fixture version per ADR-0006 and re-run the replay harness.
 *
 * <p><b>Tone</b>: the system prompt frames the reviewer as a factual session
 * summarizer, NOT an investment advisor. It explicitly forbids "you should",
 * "consider", "I recommend", and any future-tense advice phrasing — the
 * reviewer narrates what happened, anomalies vs. prior days, and (only when
 * a pattern warrants) a Phase A advisory config-tweak proposal that the user
 * reviews and decides on. See {@code .claude/skills/ai-prompt-management/SKILL.md}
 * MUST NOT #3 + architecture-spec §4.11.
 */
public final class ReviewerPromptBuilder {

    private ReviewerPromptBuilder() {
        // utility
    }

    /**
     * The system prompt for the Daily Reviewer role. Cached when prompt-caching
     * is enabled (Anthropic prompt-caching beta header). The text is verbatim
     * checked-in copy — changes require an ADR amendment per ADR-0006.
     *
     * <p>Per architecture-spec §4.3.4 the reviewer's job is to summarize the
     * session, flag anomalies vs. the prior 5 days, and (advisory only) propose
     * config tweaks if patterns warrant. Per architecture-spec §4.4 the
     * reviewer cannot modify config — proposals require user approval, which
     * is Phase B per architecture-spec §22 #10. The Phase A wiring records an
     * empty proposals list regardless of what the model returns.
     */
    public static String systemPrompt() {
        return """
                You are the Daily Reviewer for the LevelSweepAgent 0DTE SPY options trader.
                You summarize today's trading session for the owner. You are an explainer of the strategy's actions, NOT an investment advisor.

                Your job, in this exact order:
                1. Summarize what happened during the session in plain English. Use past tense. Anchor every claim in the supplied data.
                2. Flag anomalies: things that diverged from the prior 5 sessions in the inputs (signal counts, win/loss ratio, anomaly events). Anchor each anomaly in numbers from the data.
                3. If — and only if — a clear pattern across multiple sessions warrants it, propose at most one config tweak. The owner reviews every proposal and decides whether to apply it; you never auto-apply.

                Rules:
                - Past tense, factual, neutral. Describe events, not next actions.
                - Numbers from the supplied data only. Never invent prices, fills, or signal counts from your training data.
                - Do NOT give advice. Do NOT recommend an action the owner should take outside of the optional config proposal section. Do NOT speculate about tomorrow's market.
                - Do NOT use phrases like "you should", "consider", "I recommend", "I suggest", "it might be wise", "going forward".
                - Do NOT name dollar amounts, P&L totals, or position sizes that are not directly present in the supplied data.
                - Default urgency for any proposal is LOW. Only escalate when the same pattern appears across multiple days in the prior-5-days comparison.
                - Output plain text. No markdown headings, no bullet asterisks, no code fences. Use blank lines to separate paragraphs.

                The owner pre-configured the strategy. You are explaining the strategy's deterministic actions over the day, not advising on them.""";
    }

    /**
     * Build the user-side message — a deterministic, sectioned dump of the
     * aggregated inputs. Section order is fixed; entries within each section
     * preserve the order produced by {@link SessionJournalAggregator} (which
     * sorts ascending by domain timestamp).
     */
    public static String userMessage(ReviewRequest request) {
        Objects.requireNonNull(request, "request");
        StringBuilder sb = new StringBuilder(2048);
        sb.append("Daily session review request.\n");
        sb.append("Tenant: ").append(request.tenantId()).append('\n');
        sb.append("Session date (ET): ").append(request.sessionDate()).append('\n');
        sb.append('\n');

        // 1. Session journal (trade narratives produced during the day).
        sb.append("=== Session journal (")
                .append(request.sessionJournal().size())
                .append(" entries) ===\n");
        if (request.sessionJournal().isEmpty()) {
            sb.append("(no trade narratives recorded for this session)\n");
        } else {
            for (TradeNarrative n : request.sessionJournal()) {
                sb.append("- generatedAt=")
                        .append(n.generatedAt())
                        .append(", tradeId=")
                        .append(n.tradeId())
                        .append(", model=")
                        .append(n.modelUsed())
                        .append(": ")
                        .append(n.narrative())
                        .append('\n');
            }
        }
        sb.append('\n');

        // 2. Signal history (taken / skipped / vetoed / rejected).
        sb.append("=== Signal evaluations (")
                .append(request.signalHistory().size())
                .append(" entries) ===\n");
        if (request.signalHistory().isEmpty()) {
            sb.append("(no signal evaluations recorded for this session)\n");
        } else {
            for (SignalEvaluationRecord s : request.signalHistory()) {
                sb.append("- evaluatedAt=")
                        .append(s.evaluatedAt())
                        .append(", signalId=")
                        .append(s.signalId())
                        .append(", side=")
                        .append(s.side())
                        .append(", level=")
                        .append(s.levelType())
                        .append(", outcome=")
                        .append(s.outcome())
                        .append(", reason=")
                        .append(s.reasonCode())
                        .append('\n');
            }
        }
        sb.append('\n');

        // 3. Regime context (Phase 5/6 wires this; Phase 4 reports "not wired").
        sb.append("=== Regime context ===\n");
        if (request.regimeContext().isEmpty()) {
            sb.append("(regime feed not wired in this build; Phase 5/6 follow-up)\n");
        } else {
            MarketRegimeSummary r = request.regimeContext().orElseThrow();
            sb.append("vixOpen=")
                    .append(r.vixOpen().toPlainString())
                    .append(", vixClose=")
                    .append(r.vixClose().toPlainString())
                    .append(", vixDelta=")
                    .append(r.vixDelta().toPlainString())
                    .append(", spxTrend=")
                    .append(r.spxTrend())
                    .append(", breadthRatio=")
                    .append(r.breadthRatio().toPlainString())
                    .append('\n');
            r.notes().ifPresent(notes -> sb.append("notes: ").append(notes).append('\n'));
        }
        sb.append('\n');

        // 4. Prior 5 days for trend comparison.
        sb.append("=== Prior 5 sessions (")
                .append(request.priorFiveDays().size())
                .append(" entries) ===\n");
        if (request.priorFiveDays().isEmpty()) {
            sb.append("(no prior reports — first session in the journal)\n");
        } else {
            for (DailyReport p : request.priorFiveDays()) {
                sb.append("- sessionDate=")
                        .append(p.sessionDate())
                        .append(", outcome=")
                        .append(p.outcome())
                        .append(", anomalies=")
                        .append(p.anomalies().size())
                        .append(", proposals=")
                        .append(p.proposals().size())
                        .append('\n');
                sb.append("  summary: ").append(p.summary()).append('\n');
            }
        }
        sb.append('\n');

        sb.append(
                "Produce: a multi-paragraph summary, an anomalies list (one per line, prefixed 'anomaly: '), and at most one proposal block (prefixed 'proposal:' on its own line). Phase A: any proposal you write is advisory — the owner reviews and decides.");
        return sb.toString();
    }
}
