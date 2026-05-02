package com.levelsweep.aiagent.reviewer;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.aiagent.narrator.TradeNarrative;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pure tests for {@link ReviewerPromptBuilder}. Two contracts to verify:
 *
 * <ul>
 *   <li><b>Determinism</b>: same input → byte-identical output. The prompt
 *       hash relies on this for replay parity (architecture-spec Principle
 *       #2 + ADR-0006 §6).</li>
 *   <li><b>Tone</b>: the system prompt forbids advice phrasing and frames the
 *       reviewer as an explainer + factual summarizer (architecture-spec
 *       §4.11 + the {@code ai-prompt-management} skill MUST NOT #3).</li>
 * </ul>
 */
class ReviewerPromptBuilderTest {

    private static final Instant T = Instant.parse("2026-05-02T15:00:00Z");
    private static final LocalDate SESSION = LocalDate.of(2026, 5, 2);

    @Test
    void systemPromptForbidsAdviceLanguage() {
        String sys = ReviewerPromptBuilder.systemPrompt();
        // Every advisory phrase that would breach the explainer-not-advisor
        // framing must be explicitly disallowed in the prompt.
        assertThat(sys).contains("Do NOT give advice");
        assertThat(sys).contains("you should");
        assertThat(sys).contains("consider");
        assertThat(sys).contains("I recommend");
        assertThat(sys).contains("I suggest");
        assertThat(sys).contains("explainer of the strategy");
        assertThat(sys).contains("NOT an investment advisor");
    }

    @Test
    void systemPromptDescribesTheJobInOrder() {
        // Architecture-spec §4.3.4 — summary, anomalies, optional config tweak.
        String sys = ReviewerPromptBuilder.systemPrompt();
        int summaryIdx = sys.indexOf("Summarize what happened");
        int anomalyIdx = sys.indexOf("Flag anomalies");
        int proposalIdx = sys.indexOf("propose at most one config tweak");
        assertThat(summaryIdx).isGreaterThan(0);
        assertThat(anomalyIdx).isGreaterThan(summaryIdx);
        assertThat(proposalIdx).isGreaterThan(anomalyIdx);
    }

    @Test
    void systemPromptMandatesPastTenseAndDataAnchoring() {
        String sys = ReviewerPromptBuilder.systemPrompt();
        assertThat(sys).contains("Past tense");
        assertThat(sys).contains("Numbers from the supplied data");
        assertThat(sys).contains("Never invent prices");
    }

    @Test
    void systemPromptRecordsPhaseABoundary() {
        // Architecture-spec §22 #10: proposals are advisory only; user reviews
        // and decides. The system prompt must reinforce this.
        String sys = ReviewerPromptBuilder.systemPrompt();
        assertThat(sys).contains("owner reviews every proposal");
        assertThat(sys).contains("never auto-apply");
        assertThat(sys).contains("Default urgency for any proposal is LOW");
    }

    @Test
    void userMessageIncludesAllInputSections() {
        ReviewRequest req = sampleRequestWithAllSections();
        String body = ReviewerPromptBuilder.userMessage(req);
        assertThat(body).contains("Tenant: OWNER");
        assertThat(body).contains("Session date (ET): 2026-05-02");
        assertThat(body).contains("=== Session journal ");
        assertThat(body).contains("=== Signal evaluations ");
        assertThat(body).contains("=== Regime context ===");
        assertThat(body).contains("=== Prior 5 sessions ");
    }

    @Test
    void userMessageRendersTradeNarrativesInOrder() {
        ReviewRequest req = sampleRequestWithAllSections();
        String body = ReviewerPromptBuilder.userMessage(req);
        int firstIdx = body.indexOf("Entry order filled");
        int secondIdx = body.indexOf("EMA13 stop fired");
        assertThat(firstIdx).isGreaterThan(0);
        assertThat(secondIdx).isGreaterThan(firstIdx); // ascending order preserved
    }

    @Test
    void userMessageRendersSignalEvaluations() {
        ReviewRequest req = sampleRequestWithAllSections();
        String body = ReviewerPromptBuilder.userMessage(req);
        assertThat(body).contains("signalId=SIG_001");
        assertThat(body).contains("side=CALL");
        assertThat(body).contains("level=PDH");
        assertThat(body).contains("outcome=TAKEN");
        assertThat(body).contains("reason=ema48_above");
    }

    @Test
    void userMessageRendersPriorReports() {
        ReviewRequest req = sampleRequestWithAllSections();
        String body = ReviewerPromptBuilder.userMessage(req);
        assertThat(body).contains("sessionDate=2026-04-30");
        assertThat(body).contains("outcome=COMPLETED");
        assertThat(body).contains("Yesterday was uneventful");
    }

    @Test
    void userMessageReportsRegimeNotWiredWhenAbsent() {
        ReviewRequest req = sampleRequestWithAllSections();
        String body = ReviewerPromptBuilder.userMessage(req);
        // sampleRequestWithAllSections passes empty regime context.
        assertThat(body).contains("regime feed not wired");
        assertThat(body).contains("Phase 5/6 follow-up");
    }

    @Test
    void userMessageReportsRegimeWhenPresent() {
        ReviewRequest req = new ReviewRequest(
                "OWNER",
                SESSION,
                List.of(),
                List.of(),
                Optional.of(new MarketRegimeSummary(
                        new BigDecimal("16.50"),
                        new BigDecimal("17.20"),
                        new BigDecimal("0.70"),
                        MarketRegimeSummary.SpxTrend.UP,
                        new BigDecimal("1.45"),
                        Optional.of("FOMC day"))),
                List.of());
        String body = ReviewerPromptBuilder.userMessage(req);
        assertThat(body).contains("vixOpen=16.50");
        assertThat(body).contains("vixClose=17.20");
        assertThat(body).contains("vixDelta=0.70");
        assertThat(body).contains("spxTrend=UP");
        assertThat(body).contains("breadthRatio=1.45");
        assertThat(body).contains("notes: FOMC day");
    }

    @Test
    void userMessageHandlesEmptyJournal() {
        ReviewRequest req = new ReviewRequest("OWNER", SESSION, List.of(), List.of(), Optional.empty(), List.of());
        String body = ReviewerPromptBuilder.userMessage(req);
        assertThat(body).contains("(no trade narratives recorded for this session)");
        assertThat(body).contains("(no signal evaluations recorded for this session)");
        assertThat(body).contains("(no prior reports — first session in the journal)");
    }

    @Test
    void deterministic_sameInputProducesByteIdenticalOutput() {
        ReviewRequest a = sampleRequestWithAllSections();
        // Distinct record instance, identical content.
        ReviewRequest b = sampleRequestWithAllSections();
        assertThat(ReviewerPromptBuilder.userMessage(a)).isEqualTo(ReviewerPromptBuilder.userMessage(b));
        // System prompt is also stable.
        assertThat(ReviewerPromptBuilder.systemPrompt()).isEqualTo(ReviewerPromptBuilder.systemPrompt());
    }

    @Test
    void deterministic_differentSessionDateProducesDifferentBody() {
        ReviewRequest a = new ReviewRequest("OWNER", SESSION, List.of(), List.of(), Optional.empty(), List.of());
        ReviewRequest b =
                new ReviewRequest("OWNER", SESSION.plusDays(1), List.of(), List.of(), Optional.empty(), List.of());
        assertThat(ReviewerPromptBuilder.userMessage(a)).isNotEqualTo(ReviewerPromptBuilder.userMessage(b));
    }

    @Test
    void userMessageRequestsStructuredOutput() {
        ReviewRequest req = sampleRequestWithAllSections();
        String body = ReviewerPromptBuilder.userMessage(req);
        // The trailing instruction asks for summary + anomalies + (at most one)
        // proposal — section markers downstream extraction will key on.
        assertThat(body).contains("multi-paragraph summary");
        assertThat(body).contains("anomaly: ");
        assertThat(body).contains("proposal:");
        assertThat(body).contains("Phase A: any proposal you write is advisory");
    }

    // ---------- helpers ----------

    private static ReviewRequest sampleRequestWithAllSections() {
        TradeNarrative n1 = new TradeNarrative(
                "OWNER",
                "TR_001",
                "Entry order filled at $1.42 for 2 contracts.",
                Instant.parse("2026-05-02T13:35:00Z"),
                "claude-sonnet-4-6",
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08");
        TradeNarrative n2 = new TradeNarrative(
                "OWNER",
                "TR_001",
                "EMA13 stop fired and the trade exited.",
                Instant.parse("2026-05-02T14:50:00Z"),
                "claude-sonnet-4-6",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        SignalEvaluationRecord s1 = new SignalEvaluationRecord(
                "OWNER",
                SESSION,
                "SIG_001",
                Instant.parse("2026-05-02T13:32:00Z"),
                SignalEvaluationRecord.Side.CALL,
                SignalEvaluationRecord.LevelType.PDH,
                SignalEvaluationRecord.Outcome.TAKEN,
                "ema48_above",
                "corr-001");
        DailyReport prior = new DailyReport(
                "OWNER",
                LocalDate.of(2026, 4, 30),
                "Yesterday was uneventful — no signals fired.",
                List.of(),
                List.of(),
                DailyReport.Outcome.COMPLETED,
                Instant.parse("2026-04-30T20:30:00Z"),
                "claude-opus-4-7",
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                500L,
                new BigDecimal("0.0150"));
        return new ReviewRequest("OWNER", SESSION, List.of(n1, n2), List.of(s1), Optional.empty(), List.of(prior));
    }
}
