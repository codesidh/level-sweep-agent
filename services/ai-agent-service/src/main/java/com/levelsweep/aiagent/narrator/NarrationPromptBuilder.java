package com.levelsweep.aiagent.narrator;

import java.util.Objects;
import java.util.Set;

/**
 * Pure helper that turns a {@link NarrationRequest} into the user-side text
 * for the Anthropic Messages API. One template per event type; same input ->
 * byte-identical output (architecture-spec Principle #2 / replay parity, ADR
 * 0006 §6).
 *
 * <p><b>Determinism contract</b>: the produced string is the canonical input
 * to {@code com.levelsweep.aiagent.audit.PromptHasher}, so the prompt hash is
 * stable across runs of the same listener over the same domain event. Any
 * change to a template body is a replay-parity-breaking change — bump the
 * fixture version per ADR-0006 and re-run the replay harness.
 *
 * <p><b>Tone</b>: the templates ask for "explanation in 1-3 sentences" and
 * forbid "advice / recommendations / suggestions" — the system prompt repeats
 * this guard but a single line per template reinforces it on every call. See
 * {@code .claude/skills/ai-prompt-management/SKILL.md} MUST NOT #3.
 */
public final class NarrationPromptBuilder {

    /** Trade entry order filled (full or partial). */
    public static final String EVENT_FILL = "FILL";

    /** Alpaca rejected the entry order (4xx / 5xx / transport failure). */
    public static final String EVENT_REJECTED = "REJECTED";

    /** Stop watcher fired — 2-min bar close violated EMA13 / EMA48 stop. */
    public static final String EVENT_STOP = "STOP";

    /** Trail manager observed NBBO retrace below the armed exit floor. */
    public static final String EVENT_TRAIL_BREACH = "TRAIL_BREACH";

    /** EOD flatten cron force-closed the trade at 15:55 ET. */
    public static final String EVENT_EOD_FLATTEN = "EOD_FLATTEN";

    /** Order accepted by Alpaca; awaiting fill. */
    public static final String EVENT_ORDER_SUBMITTED = "ORDER_SUBMITTED";

    /** Closed set of supported event types. {@link NarrationRequest} validates against this. */
    public static final Set<String> KNOWN_EVENT_TYPES = Set.of(
            EVENT_FILL, EVENT_REJECTED, EVENT_STOP, EVENT_TRAIL_BREACH, EVENT_EOD_FLATTEN, EVENT_ORDER_SUBMITTED);

    private NarrationPromptBuilder() {
        // utility
    }

    /**
     * The system prompt for the Trade Narrator role. Cached when prompt-caching
     * is enabled (Anthropic prompt-caching beta header). The text is verbatim
     * checked-in copy — changes require an ADR amendment per ADR-0006.
     *
     * <p>Note the explicit "do NOT give advice" framing per CLAUDE.md AI
     * guardrail #3 ("Never log or commit credentials" is unrelated; the
     * advisory-not-advisor framing is in the {@code ai-prompt-management}
     * skill MUST NOT #3) and architecture-spec §4.11 ("agent positioned as
     * strategy executor and explainer, not advisor").
     */
    public static String systemPrompt() {
        return """
                You are the Trade Narrator for the LevelSweepAgent 0DTE SPY options trader.
                Your job is to explain to the trader, in 1 to 3 short sentences, what just happened on a trade.

                Rules:
                - Plain English. Avoid jargon unless it directly names the rule that fired (e.g. "EMA13", "trailing stop").
                - Past tense, factual, neutral. Describe the event, not what to do next.
                - Do NOT give advice. Do NOT recommend an action. Do NOT speculate about future trades.
                - Do NOT use phrases like "you should", "consider", "I suggest", "it might be wise".
                - Output the narrative text only. No preamble, no headings, no bullet lists, no markdown.
                - Maximum 3 sentences. Do not write a paragraph.

                The trader pre-configured the strategy. You are explaining the strategy's deterministic action,
                not advising on it.""";
    }

    /** Build the user-side message for a single inbound event. */
    public static String userMessage(NarrationRequest request) {
        Objects.requireNonNull(request, "request");
        return switch (request.eventType()) {
            case EVENT_FILL -> fillTemplate(request);
            case EVENT_REJECTED -> rejectedTemplate(request);
            case EVENT_STOP -> stopTemplate(request);
            case EVENT_TRAIL_BREACH -> trailBreachTemplate(request);
            case EVENT_EOD_FLATTEN -> eodFlattenTemplate(request);
            case EVENT_ORDER_SUBMITTED -> orderSubmittedTemplate(request);
            default ->
            // Unreachable — NarrationRequest validates the event type at
            // construction. Defensive default kept so a future event type
            // added without a template fails loudly rather than silently.
            throw new IllegalStateException("no template for eventType=" + request.eventType());
        };
    }

    // ---------------------------------------------------------------------
    // Templates — one per event type. Each FIRST LINE is a one-line
    // explanation prompt; the second block carries the deterministic event
    // payload built by the listener mappers.
    // ---------------------------------------------------------------------

    private static String fillTemplate(NarrationRequest r) {
        return """
                Explain in 1-3 sentences that the entry order for this trade was filled by the broker.
                Mention the contract symbol and the filled price; do not recommend any next action.

                Event: trade entry filled
                Trade ID: %s
                Occurred at: %s
                Details: %s"""
                .formatted(r.tradeId(), r.occurredAt(), r.eventPayload());
    }

    private static String rejectedTemplate(NarrationRequest r) {
        return """
                Explain in 1-3 sentences that the entry order for this trade was rejected by the broker and did not enter the market.
                Mention the rejection reason if visible; do not recommend any next action.

                Event: trade entry rejected
                Trade ID: %s
                Occurred at: %s
                Details: %s"""
                .formatted(r.tradeId(), r.occurredAt(), r.eventPayload());
    }

    private static String stopTemplate(NarrationRequest r) {
        return """
                Explain in 1-3 sentences that the deterministic stop-loss rule fired and the trade is exiting.
                Mention which moving average was the stop reference (EMA13 or EMA48) and that this was a rule, not a discretionary call; do not recommend any next action.

                Event: stop-loss triggered
                Trade ID: %s
                Occurred at: %s
                Details: %s"""
                .formatted(r.tradeId(), r.occurredAt(), r.eventPayload());
    }

    private static String trailBreachTemplate(NarrationRequest r) {
        return """
                Explain in 1-3 sentences that the trailing-stop floor was breached and the trade is exiting at the locked-in profit level.
                Mention the exit floor percentage if visible; do not recommend any next action.

                Event: trailing stop floor breached
                Trade ID: %s
                Occurred at: %s
                Details: %s"""
                .formatted(r.tradeId(), r.occurredAt(), r.eventPayload());
    }

    private static String eodFlattenTemplate(NarrationRequest r) {
        return """
                Explain in 1-3 sentences that the end-of-day flatten rule force-closed this trade because 0DTE options must not be held past the close.
                State that this is automatic and unconditional; do not recommend any next action.

                Event: end-of-day flatten
                Trade ID: %s
                Occurred at: %s
                Details: %s"""
                .formatted(r.tradeId(), r.occurredAt(), r.eventPayload());
    }

    private static String orderSubmittedTemplate(NarrationRequest r) {
        return """
                Explain in 1-3 sentences that the entry order has been accepted by the broker and is now waiting for a fill.
                Mention the contract symbol and quantity if visible; do not recommend any next action.

                Event: entry order submitted
                Trade ID: %s
                Occurred at: %s
                Details: %s"""
                .formatted(r.tradeId(), r.occurredAt(), r.eventPayload());
    }
}
