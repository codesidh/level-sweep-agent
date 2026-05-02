package com.levelsweep.aiagent.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.levelsweep.aiagent.anthropic.AnthropicClient;
import com.levelsweep.aiagent.anthropic.AnthropicRequest;
import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
import com.levelsweep.aiagent.anthropic.AnthropicResponse;
import com.levelsweep.aiagent.audit.AiCallAuditWriter;
import com.levelsweep.aiagent.cost.DailyCostTracker;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DailyReviewer}. Covers:
 *
 * <ul>
 *   <li>Success path: AnthropicClient → Success → DailyReport with full
 *       token + cost accounting + COMPLETED outcome.</li>
 *   <li>Cost-cap pre-check returns true → review returns Optional.empty
 *       BEFORE any Anthropic call + audit row.</li>
 *   <li>TransportFailure / RateLimited / Overloaded / InvalidRequest /
 *       CostCapBreached → Optional.empty, audit row STILL written.</li>
 *   <li>Empty response text → Optional.empty (would not validate the record),
 *       audit row still written.</li>
 *   <li>Determinism: identical ReviewRequest → identical promptHash on the
 *       resulting DailyReport (replay parity).</li>
 *   <li>Submitted AnthropicRequest carries: temperature=0, maxTokens=1500,
 *       model=claude-opus-4-7, role=REVIEWER, tenantId from inbound request,
 *       retryEnabled=false (single attempt).</li>
 *   <li>Phase A boundary: persisted DailyReport carries empty proposals +
 *       empty anomalies regardless of model output.</li>
 *   <li>Audit-writer throw must not propagate.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DailyReviewerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-05-02T20:30:00Z"), ZoneOffset.UTC);
    private static final String MODEL = "claude-opus-4-7";
    private static final String TENANT = "OWNER";
    private static final LocalDate SESSION = LocalDate.of(2026, 5, 2);

    @Mock
    private AnthropicClient anthropicClient;

    @Mock
    private DailyCostTracker costTracker;

    @Mock
    private AiCallAuditWriter auditWriter;

    private DailyReviewer reviewer;

    @BeforeEach
    void setUp() {
        reviewer = new DailyReviewer(anthropicClient, costTracker, auditWriter, FIXED_CLOCK, MODEL);
        when(costTracker.today()).thenReturn(LocalDate.of(2026, 5, 2));
    }

    @Test
    void successProducesCompletedReport() {
        when(costTracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean()))
                .thenReturn(success("Today the strategy executed two trades. Both exited via EMA13 stop."));

        Optional<DailyReport> result = reviewer.review(emptyRequest());

        assertThat(result).isPresent();
        DailyReport r = result.orElseThrow();
        assertThat(r.tenantId()).isEqualTo(TENANT);
        assertThat(r.sessionDate()).isEqualTo(SESSION);
        assertThat(r.summary()).isEqualTo("Today the strategy executed two trades. Both exited via EMA13 stop.");
        assertThat(r.outcome()).isEqualTo(DailyReport.Outcome.COMPLETED);
        assertThat(r.modelUsed()).isEqualTo(MODEL);
        assertThat(r.promptHash()).hasSize(64); // SHA-256 hex
        assertThat(r.generatedAt()).isEqualTo(FIXED_CLOCK.instant());
        // Phase A: proposals + anomalies always empty (architecture-spec §22 #10).
        assertThat(r.proposals()).isEmpty();
        assertThat(r.anomalies()).isEmpty();
        // Token accounting reconciled from response.usage.
        assertThat(r.totalTokensUsed()).isEqualTo(11_500L); // 10000 input + 1500 output + 0 cached
        assertThat(r.costUsd()).isEqualByComparingTo(new BigDecimal("0.2625"));

        verify(auditWriter, times(1)).record(any(), any(AnthropicResponse.Success.class), eq(""));
        verify(costTracker, times(1)).recordCost(eq(TENANT), eq(Role.REVIEWER), any(), any());
    }

    @Test
    void costCapPreCheckShortCircuitsBeforeHttpAndAudit() {
        when(costTracker.wouldExceedCap(eq(TENANT), eq(Role.REVIEWER), any(), any()))
                .thenReturn(true);
        when(costTracker.capFor(Role.REVIEWER)).thenReturn(new BigDecimal("1.00"));
        when(costTracker.currentSpend(eq(TENANT), eq(Role.REVIEWER), any())).thenReturn(new BigDecimal("0.99"));

        Optional<DailyReport> result = reviewer.review(emptyRequest());

        assertThat(result).isEmpty();
        // Critical: NO Anthropic call attempted.
        verify(anthropicClient, never()).submit(any(AnthropicRequest.class), anyBoolean());
        // No audit row written either — the call never happened. The scheduler
        // separately persists a SKIPPED_COST_CAP stub for audit consistency.
        verify(auditWriter, never()).record(any(), any(), any());
        verify(costTracker, never()).recordCost(any(), any(), any(), any());
    }

    @Test
    void transportFailureReturnsEmptyButStillAudits() {
        when(costTracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean()))
                .thenReturn(
                        new AnthropicResponse.TransportFailure("req_1", Role.REVIEWER, MODEL, 25L, "missing api key"));

        Optional<DailyReport> result = reviewer.review(emptyRequest());

        assertThat(result).isEmpty();
        verify(auditWriter, times(1)).record(any(), any(AnthropicResponse.TransportFailure.class), eq(""));
        verify(costTracker, never()).recordCost(any(), any(), any(), any());
    }

    @Test
    void rateLimitedReturnsEmptyButStillAudits() {
        when(costTracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean()))
                .thenReturn(new AnthropicResponse.RateLimited("req_2", Role.REVIEWER, MODEL, 100L, "rate limited"));

        Optional<DailyReport> result = reviewer.review(emptyRequest());

        assertThat(result).isEmpty();
        verify(auditWriter, times(1)).record(any(), any(AnthropicResponse.RateLimited.class), eq(""));
    }

    @Test
    void overloadedReturnsEmptyButStillAudits() {
        when(costTracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean()))
                .thenReturn(new AnthropicResponse.Overloaded("req_3", Role.REVIEWER, MODEL, 80L, 529, "overloaded"));

        Optional<DailyReport> result = reviewer.review(emptyRequest());

        assertThat(result).isEmpty();
        verify(auditWriter, times(1)).record(any(), any(AnthropicResponse.Overloaded.class), eq(""));
    }

    @Test
    void invalidRequestReturnsEmptyButStillAudits() {
        when(costTracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean()))
                .thenReturn(new AnthropicResponse.InvalidRequest("req_4", Role.REVIEWER, MODEL, 25L, 400, "bad shape"));

        Optional<DailyReport> result = reviewer.review(emptyRequest());

        assertThat(result).isEmpty();
        verify(auditWriter, times(1)).record(any(), any(AnthropicResponse.InvalidRequest.class), eq(""));
    }

    @Test
    void costCapBreachedFromClientReturnsEmpty() {
        // Pre-check passed, but a concurrent call consumed the headroom and
        // the client itself short-circuited.
        when(costTracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean()))
                .thenReturn(new AnthropicResponse.CostCapBreached(
                        "req_5",
                        Role.REVIEWER,
                        MODEL,
                        0L,
                        new BigDecimal("1.00"),
                        new BigDecimal("0.99"),
                        new BigDecimal("0.2625")));

        Optional<DailyReport> result = reviewer.review(emptyRequest());

        assertThat(result).isEmpty();
        verify(auditWriter, times(1)).record(any(), any(AnthropicResponse.CostCapBreached.class), eq(""));
    }

    @Test
    void emptyResponseTextReturnsEmpty() {
        when(costTracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean())).thenReturn(success("   "));

        Optional<DailyReport> result = reviewer.review(emptyRequest());

        assertThat(result).isEmpty();
        // Audit row still written, but no report produced.
        verify(auditWriter, times(1)).record(any(), any(AnthropicResponse.Success.class), eq(""));
    }

    @Test
    void deterministic_identicalRequestProducesIdenticalPromptHash() {
        when(costTracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean())).thenReturn(success("ok."));

        ReviewRequest req = emptyRequest();
        Optional<DailyReport> r1 = reviewer.review(req);
        Optional<DailyReport> r2 = reviewer.review(req);

        assertThat(r1).isPresent();
        assertThat(r2).isPresent();
        assertThat(r1.get().promptHash()).isEqualTo(r2.get().promptHash());
    }

    @Test
    void submittedAnthropicRequestIsConfiguredCorrectly() {
        when(costTracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean())).thenReturn(success("ok."));

        reviewer.review(emptyRequest());

        ArgumentCaptor<AnthropicRequest> captor = ArgumentCaptor.forClass(AnthropicRequest.class);
        ArgumentCaptor<Boolean> retry = ArgumentCaptor.forClass(Boolean.class);
        verify(anthropicClient).submit(captor.capture(), retry.capture());

        AnthropicRequest aReq = captor.getValue();
        assertThat(aReq.model()).isEqualTo(MODEL);
        assertThat(aReq.role()).isEqualTo(Role.REVIEWER);
        assertThat(aReq.tenantId()).isEqualTo(TENANT);
        assertThat(aReq.maxTokens()).isEqualTo(DailyReviewer.MAX_OUTPUT_TOKENS);
        assertThat(aReq.temperature()).isEqualTo(0.0d);
        assertThat(aReq.tools()).isEmpty();
        assertThat(aReq.messages()).hasSize(1);
        assertThat(aReq.messages().get(0).role()).isEqualTo("user");
        assertThat(aReq.systemPrompt()).contains("Daily Reviewer");
        // Reviewer is single-attempt — if Anthropic is down at 16:30 ET we
        // skip the day; tomorrow's run picks up.
        assertThat(retry.getValue()).isFalse();
    }

    @Test
    void userMessageInRequestContainsKeySectionsInDeterministicOrder() {
        when(costTracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean())).thenReturn(success("ok."));

        reviewer.review(emptyRequest());

        ArgumentCaptor<AnthropicRequest> captor = ArgumentCaptor.forClass(AnthropicRequest.class);
        verify(anthropicClient).submit(captor.capture(), anyBoolean());
        String userMsg = captor.getValue().messages().get(0).content();
        // Section order is contractual — see ReviewerPromptBuilder.
        int journalIdx = userMsg.indexOf("=== Session journal ");
        int signalsIdx = userMsg.indexOf("=== Signal evaluations ");
        int regimeIdx = userMsg.indexOf("=== Regime context ===");
        int priorIdx = userMsg.indexOf("=== Prior 5 sessions ");
        assertThat(journalIdx).isGreaterThan(0);
        assertThat(signalsIdx).isGreaterThan(journalIdx);
        assertThat(regimeIdx).isGreaterThan(signalsIdx);
        assertThat(priorIdx).isGreaterThan(regimeIdx);
    }

    @Test
    void neverThrowsWhenAuditWriterThrows() {
        when(costTracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean())).thenReturn(success("today's review."));
        org.mockito.Mockito.doThrow(new RuntimeException("mongo down"))
                .when(auditWriter)
                .record(any(), any(), any());

        // Even though the audit writer throws, the reviewer must produce the
        // report + return success. Audit failures must never propagate
        // (CLAUDE.md guardrail #3 — reviewer advisory).
        Optional<DailyReport> result = reviewer.review(emptyRequest());

        assertThat(result).isPresent();
        assertThat(result.get().summary()).isEqualTo("today's review.");
    }

    // ---------- helpers ----------

    private static ReviewRequest emptyRequest() {
        return new ReviewRequest(TENANT, SESSION, List.of(), List.of(), Optional.empty(), List.of());
    }

    private static AnthropicResponse.Success success(String text) {
        // Opus 4.7 pricing: $15/MTok input, $75/MTok output.
        // 10000 input * $15/1M + 1500 output * $75/1M = 0.15 + 0.1125 = 0.2625
        return new AnthropicResponse.Success(
                "req_ok",
                Role.REVIEWER,
                MODEL,
                420L,
                text,
                List.of(),
                /* inputTokens */ 10_000,
                /* outputTokens */ 1_500,
                /* cachedTokens */ 0,
                new BigDecimal("0.2625"));
    }
}
