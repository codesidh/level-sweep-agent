package com.levelsweep.aiagent.narrator;

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
import com.levelsweep.aiagent.observability.AiAgentMetrics;
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
 * Unit tests for {@link TradeNarrator}. Covers:
 *
 * <ul>
 *   <li>Each of the 6 event types produces a Success → TradeNarrative path.</li>
 *   <li>Cost-cap pre-check: tracker returns true → narrator skips, returns
 *       Optional.empty, NO Anthropic call, NO audit row.</li>
 *   <li>TransportFailure (e.g. missing API key, DNS failure) → Optional.empty,
 *       audit row STILL written, no recordCost.</li>
 *   <li>RateLimited / Overloaded / InvalidRequest variants → Optional.empty,
 *       audit row written.</li>
 *   <li>Determinism: identical NarrationRequest → identical promptHash on the
 *       resulting TradeNarrative (replay parity).</li>
 *   <li>The submitted AnthropicRequest carries: temperature=0, max_tokens=200,
 *       model=claude-sonnet-4-6, role=NARRATOR, tenantId from inbound event.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TradeNarratorTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-05-02T15:30:00Z"), ZoneOffset.UTC);
    private static final String MODEL = "claude-sonnet-4-6";
    private static final String TENANT = "OWNER";
    private static final String TRADE_ID = "TR_2026-05-02_001";
    private static final Instant OCCURRED_AT = Instant.parse("2026-05-02T13:32:30Z");

    @Mock
    private AnthropicClient anthropicClient;

    @Mock
    private DailyCostTracker costTracker;

    @Mock
    private AiCallAuditWriter auditWriter;

    private TradeNarrator narrator;

    @BeforeEach
    void setUp() {
        narrator =
                new TradeNarrator(anthropicClient, costTracker, auditWriter, AiAgentMetrics.noop(), FIXED_CLOCK, MODEL);
        // Default: ample budget, never blocked.
        when(costTracker.today()).thenReturn(LocalDate.of(2026, 5, 2));
        // wouldExceedCap default → false. We pin per-test when we need true.
    }

    @Test
    void fillEventProducesNarrative() {
        when(costTracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean()))
                .thenReturn(success("Entry order filled at $1.42 for 2 contracts."));

        Optional<TradeNarrative> result = narrator.narrate(new NarrationRequest(
                TENANT,
                NarrationPromptBuilder.EVENT_FILL,
                "contract=SPY250502C00500000, filledAvgPrice=1.42, filledQty=2",
                TRADE_ID,
                OCCURRED_AT));

        assertThat(result).isPresent();
        TradeNarrative n = result.orElseThrow();
        assertThat(n.tenantId()).isEqualTo(TENANT);
        assertThat(n.tradeId()).isEqualTo(TRADE_ID);
        assertThat(n.narrative()).isEqualTo("Entry order filled at $1.42 for 2 contracts.");
        assertThat(n.modelUsed()).isEqualTo(MODEL);
        assertThat(n.promptHash()).hasSize(64); // SHA-256 hex
        assertThat(n.generatedAt()).isEqualTo(FIXED_CLOCK.instant());
        verify(auditWriter, times(1)).record(any(), any(AnthropicResponse.Success.class), eq(""));
        verify(costTracker, times(1)).recordCost(eq(TENANT), eq(Role.NARRATOR), any(), any());
    }

    @Test
    void allSixEventTypesProduceNarratives() {
        when(costTracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean())).thenReturn(success("done."));

        for (String eventType : NarrationPromptBuilder.KNOWN_EVENT_TYPES) {
            Optional<TradeNarrative> result =
                    narrator.narrate(new NarrationRequest(TENANT, eventType, "x=1", TRADE_ID, OCCURRED_AT));
            assertThat(result)
                    .as("event %s should produce narrative", eventType)
                    .isPresent();
        }
    }

    @Test
    void costCapPreCheckShortCircuitsBeforeHttpAndAudit() {
        when(costTracker.wouldExceedCap(eq(TENANT), eq(Role.NARRATOR), any(), any()))
                .thenReturn(true);
        when(costTracker.capFor(Role.NARRATOR)).thenReturn(new BigDecimal("1.00"));
        when(costTracker.currentSpend(eq(TENANT), eq(Role.NARRATOR), any())).thenReturn(new BigDecimal("0.99"));

        Optional<TradeNarrative> result = narrator.narrate(
                new NarrationRequest(TENANT, NarrationPromptBuilder.EVENT_FILL, "x=1", TRADE_ID, OCCURRED_AT));

        assertThat(result).isEmpty();
        // Critical: NO Anthropic call attempted.
        verify(anthropicClient, never()).submit(any(AnthropicRequest.class), anyBoolean());
        // No audit row written either — the call never happened.
        verify(auditWriter, never()).record(any(), any(), any());
        // No cost recorded.
        verify(costTracker, never()).recordCost(any(), any(), any(), any());
    }

    @Test
    void transportFailureReturnsEmptyButStillAudits() {
        when(costTracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean()))
                .thenReturn(
                        new AnthropicResponse.TransportFailure("req_1", Role.NARRATOR, MODEL, 25L, "missing api key"));

        Optional<TradeNarrative> result = narrator.narrate(
                new NarrationRequest(TENANT, NarrationPromptBuilder.EVENT_FILL, "x=1", TRADE_ID, OCCURRED_AT));

        assertThat(result).isEmpty();
        // Audit row STILL written (variant: TransportFailure).
        verify(auditWriter, times(1)).record(any(), any(AnthropicResponse.TransportFailure.class), eq(""));
        // No cost recorded — the call did not actually consume tokens.
        verify(costTracker, never()).recordCost(any(), any(), any(), any());
    }

    @Test
    void rateLimitedReturnsEmptyButStillAudits() {
        when(costTracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean()))
                .thenReturn(new AnthropicResponse.RateLimited("req_2", Role.NARRATOR, MODEL, 100L, "rate limited"));

        Optional<TradeNarrative> result = narrator.narrate(
                new NarrationRequest(TENANT, NarrationPromptBuilder.EVENT_REJECTED, "x=1", TRADE_ID, OCCURRED_AT));

        assertThat(result).isEmpty();
        verify(auditWriter, times(1)).record(any(), any(AnthropicResponse.RateLimited.class), eq(""));
        verify(costTracker, never()).recordCost(any(), any(), any(), any());
    }

    @Test
    void costCapBreachedFromClientReturnsEmpty() {
        // The client itself reports cap breach (e.g. our pre-check passed but
        // a concurrent call consumed the headroom in between). Narrator still
        // returns empty.
        when(costTracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean()))
                .thenReturn(new AnthropicResponse.CostCapBreached(
                        "req_3",
                        Role.NARRATOR,
                        MODEL,
                        0L,
                        new BigDecimal("1.00"),
                        new BigDecimal("0.99"),
                        new BigDecimal("0.0120")));

        Optional<TradeNarrative> result = narrator.narrate(
                new NarrationRequest(TENANT, NarrationPromptBuilder.EVENT_STOP, "x=1", TRADE_ID, OCCURRED_AT));

        assertThat(result).isEmpty();
        verify(auditWriter, times(1)).record(any(), any(AnthropicResponse.CostCapBreached.class), eq(""));
    }

    @Test
    void emptyResponseTextReturnsEmpty() {
        when(costTracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean())).thenReturn(success("   "));

        Optional<TradeNarrative> result = narrator.narrate(
                new NarrationRequest(TENANT, NarrationPromptBuilder.EVENT_FILL, "x=1", TRADE_ID, OCCURRED_AT));

        assertThat(result).isEmpty();
        // Audit row still written, but no narrative produced.
        verify(auditWriter, times(1)).record(any(), any(AnthropicResponse.Success.class), eq(""));
    }

    @Test
    void deterministic_identicalRequestProducesIdenticalPromptHash() {
        when(costTracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean())).thenReturn(success("ok."));

        NarrationRequest req = new NarrationRequest(
                TENANT, NarrationPromptBuilder.EVENT_FILL, "contract=SPY,price=1.42", TRADE_ID, OCCURRED_AT);

        Optional<TradeNarrative> n1 = narrator.narrate(req);
        Optional<TradeNarrative> n2 = narrator.narrate(req);

        assertThat(n1).isPresent();
        assertThat(n2).isPresent();
        assertThat(n1.get().promptHash()).isEqualTo(n2.get().promptHash());
    }

    @Test
    void submittedAnthropicRequestIsConfiguredCorrectly() {
        when(costTracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean())).thenReturn(success("ok."));

        narrator.narrate(new NarrationRequest(TENANT, NarrationPromptBuilder.EVENT_FILL, "x=1", TRADE_ID, OCCURRED_AT));

        ArgumentCaptor<AnthropicRequest> captor = ArgumentCaptor.forClass(AnthropicRequest.class);
        ArgumentCaptor<Boolean> retry = ArgumentCaptor.forClass(Boolean.class);
        verify(anthropicClient).submit(captor.capture(), retry.capture());

        AnthropicRequest aReq = captor.getValue();
        assertThat(aReq.model()).isEqualTo(MODEL);
        assertThat(aReq.role()).isEqualTo(Role.NARRATOR);
        assertThat(aReq.tenantId()).isEqualTo(TENANT);
        assertThat(aReq.maxTokens()).isEqualTo(TradeNarrator.MAX_OUTPUT_TOKENS);
        assertThat(aReq.temperature()).isEqualTo(0.0d);
        assertThat(aReq.tools()).isEmpty();
        assertThat(aReq.messages()).hasSize(1);
        assertThat(aReq.messages().get(0).role()).isEqualTo("user");
        assertThat(aReq.systemPrompt()).contains("Trade Narrator");
        // Narrator wires retry-enabled per ADR-0006 §7 (architecture-spec §4.9
        // — Narrator/Reviewer queue with exponential backoff).
        assertThat(retry.getValue()).isTrue();
    }

    @Test
    void neverThrowsWhenAuditWriterThrows() {
        when(costTracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean())).thenReturn(success("ok."));
        org.mockito.Mockito.doThrow(new RuntimeException("mongo down"))
                .when(auditWriter)
                .record(any(), any(), any());

        // Even though the audit writer throws, the narrator must produce the
        // narrative + return success. Audit failures must never propagate
        // (CLAUDE.md guardrail #3 — narrator advisory).
        Optional<TradeNarrative> result = narrator.narrate(
                new NarrationRequest(TENANT, NarrationPromptBuilder.EVENT_FILL, "x=1", TRADE_ID, OCCURRED_AT));

        assertThat(result).isPresent();
        assertThat(result.get().narrative()).isEqualTo("ok.");
    }

    // ---------- helpers ----------

    private static AnthropicResponse.Success success(String text) {
        return new AnthropicResponse.Success(
                "req_ok",
                Role.NARRATOR,
                MODEL,
                42L,
                text,
                List.of(),
                /* inputTokens */ 1500,
                /* outputTokens */ 50,
                /* cachedTokens */ 0,
                new BigDecimal("0.0053"));
    }
}
