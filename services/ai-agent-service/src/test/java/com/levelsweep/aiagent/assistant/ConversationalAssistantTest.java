package com.levelsweep.aiagent.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.levelsweep.aiagent.anthropic.AnthropicClient;
import com.levelsweep.aiagent.anthropic.AnthropicRequest;
import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
import com.levelsweep.aiagent.anthropic.AnthropicResponse;
import com.levelsweep.aiagent.observability.AiAgentMetrics;
import io.micrometer.core.instrument.Counter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ConversationalAssistant}. Mockito over the
 * AnthropicClient + the conversation repository — no Mongo, no HTTP. Covers:
 *
 * <ul>
 *   <li>New conversation flow: id is generated, both turns persisted.</li>
 *   <li>Existing conversation flow: same id reused, turns appended.</li>
 *   <li>All 5 failure variants (RateLimited / Overloaded / InvalidRequest /
 *       TransportFailure / CostCapBreached) → synthetic turn, no persist of
 *       assistant turn, metric incremented.</li>
 *   <li>Empty response text → synthetic turn, no persist.</li>
 *   <li>Context truncation: > maxContextTurns → "[earlier turns truncated]"
 *       preamble + only the most recent N turns sent.</li>
 *   <li>System prompt enforces "READ-ONLY".</li>
 *   <li>Metrics: fired + tokens(input|output) on success; failed{reason} on
 *       each failure variant.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ConversationalAssistantTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-05-04T13:30:00Z"), ZoneOffset.UTC);
    private static final String TENANT = "OWNER";
    private static final String MODEL = "claude-sonnet-4-6";

    @Mock
    private AnthropicClient anthropicClient;

    @Mock
    private AssistantConversationRepository repository;

    private AiAgentMetrics metrics;
    private ConversationalAssistant assistant;

    @BeforeEach
    void setUp() {
        metrics = AiAgentMetrics.noop();
        assistant = new ConversationalAssistant(
                anthropicClient,
                repository,
                metrics,
                FIXED_CLOCK,
                MODEL,
                /* maxContextTurns */ 20,
                /* systemPromptVersion */ "v1");
    }

    @Test
    void newConversationGeneratesIdAndPersistsBothTurns() {
        AssistantConversation fresh = freshConversation("conv-new");
        when(repository.createNew(TENANT)).thenReturn(fresh);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean()))
                .thenReturn(success("R-multiple was +1.2 on TR_001."));

        ConversationalAssistant.ChatResult result = assistant.chat(TENANT, null, "what was today's R-multiple?");

        assertThat(result.conversationId()).isEqualTo("conv-new");
        assertThat(result.turn().role()).isEqualTo("assistant");
        assertThat(result.turn().content()).isEqualTo("R-multiple was +1.2 on TR_001.");
        // Both user + assistant turns persisted.
        verify(repository, times(1)).appendTurn(eq(TENANT), eq("conv-new"), userTurnArg(), any(BigDecimal.class));
        verify(repository, times(1)).appendTurn(eq(TENANT), eq("conv-new"), assistantTurnArg(), any(BigDecimal.class));
        // Metrics — fired + 2 tokens counters.
        assertThat(counterCount(AiAgentMetrics.METER_ASSISTANT_FIRED)).isEqualTo(1);
    }

    @Test
    void existingConversationReusesIdAndAppendsTurns() {
        AssistantConversation existing = new AssistantConversation(
                TENANT,
                "conv-existing",
                Instant.parse("2026-05-04T13:00:00Z"),
                Instant.parse("2026-05-04T13:25:00Z"),
                List.of(
                        AssistantTurn.user("yesterday's pnl?", Instant.parse("2026-05-04T13:24:30Z")),
                        AssistantTurn.assistant(
                                "+$420.", Instant.parse("2026-05-04T13:25:00Z"), new BigDecimal("0.012"))),
                new BigDecimal("0.012"));
        when(repository.findById(TENANT, "conv-existing")).thenReturn(Optional.of(existing));
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean())).thenReturn(success("answer."));

        ConversationalAssistant.ChatResult result = assistant.chat(TENANT, "conv-existing", "and today's?");

        assertThat(result.conversationId()).isEqualTo("conv-existing");
        verify(repository, never()).createNew(any());
        verify(repository, times(2)).appendTurn(eq(TENANT), eq("conv-existing"), any(), any());
    }

    @Test
    void unknownConversationIdFallsBackToNew() {
        AssistantConversation fresh = freshConversation("conv-fresh");
        when(repository.findById(TENANT, "stale-id")).thenReturn(Optional.empty());
        when(repository.createNew(TENANT)).thenReturn(fresh);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean())).thenReturn(success("ok."));

        ConversationalAssistant.ChatResult result = assistant.chat(TENANT, "stale-id", "hi");

        assertThat(result.conversationId()).isEqualTo("conv-fresh");
    }

    @Test
    void rateLimitedReturnsSyntheticTurnAndDoesNotPersistAssistantTurn() {
        AssistantConversation fresh = freshConversation("conv-1");
        when(repository.createNew(TENANT)).thenReturn(fresh);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean()))
                .thenReturn(new AnthropicResponse.RateLimited("req_1", Role.ASSISTANT, MODEL, 5L, "rate limited"));

        ConversationalAssistant.ChatResult result = assistant.chat(TENANT, null, "hi");

        assertThat(result.turn().content()).isEqualTo(ConversationalAssistant.TRANSIENT_FAILURE_MESSAGE);
        // Only the user turn was persisted; the assistant turn for an error is
        // a synthetic, in-memory-only message.
        verify(repository, times(1)).appendTurn(any(), any(), userTurnArg(), any());
        verify(repository, never()).appendTurn(any(), any(), assistantTurnArg(), any());
        assertThat(failureCount(AiAgentMetrics.AssistantFailureReason.ANTHROPIC_FAILURE))
                .isEqualTo(1);
    }

    @Test
    void overloadedReturnsSynthetic() {
        AssistantConversation fresh = freshConversation("conv-2");
        when(repository.createNew(TENANT)).thenReturn(fresh);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean()))
                .thenReturn(new AnthropicResponse.Overloaded("req_2", Role.ASSISTANT, MODEL, 10L, 503, "overloaded"));

        ConversationalAssistant.ChatResult result = assistant.chat(TENANT, null, "hi");

        assertThat(result.turn().content()).isEqualTo(ConversationalAssistant.TRANSIENT_FAILURE_MESSAGE);
        assertThat(failureCount(AiAgentMetrics.AssistantFailureReason.ANTHROPIC_FAILURE))
                .isEqualTo(1);
    }

    @Test
    void invalidRequestReturnsSynthetic() {
        AssistantConversation fresh = freshConversation("conv-3");
        when(repository.createNew(TENANT)).thenReturn(fresh);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean()))
                .thenReturn(new AnthropicResponse.InvalidRequest("req_3", Role.ASSISTANT, MODEL, 5L, 400, "bad"));

        ConversationalAssistant.ChatResult result = assistant.chat(TENANT, null, "hi");

        assertThat(result.turn().content()).isEqualTo(ConversationalAssistant.TRANSIENT_FAILURE_MESSAGE);
        assertThat(failureCount(AiAgentMetrics.AssistantFailureReason.ANTHROPIC_FAILURE))
                .isEqualTo(1);
    }

    @Test
    void transportFailureReturnsSyntheticAndCountsTimeout() {
        AssistantConversation fresh = freshConversation("conv-4");
        when(repository.createNew(TENANT)).thenReturn(fresh);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean()))
                .thenReturn(
                        new AnthropicResponse.TransportFailure("req_4", Role.ASSISTANT, MODEL, 25L, "missing api key"));

        ConversationalAssistant.ChatResult result = assistant.chat(TENANT, null, "hi");

        assertThat(result.turn().content()).isEqualTo(ConversationalAssistant.TRANSIENT_FAILURE_MESSAGE);
        assertThat(failureCount(AiAgentMetrics.AssistantFailureReason.TIMEOUT)).isEqualTo(1);
    }

    @Test
    void costCapBreachedReturnsCapSpecificMessage() {
        AssistantConversation fresh = freshConversation("conv-5");
        when(repository.createNew(TENANT)).thenReturn(fresh);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean()))
                .thenReturn(new AnthropicResponse.CostCapBreached(
                        "req_5",
                        Role.ASSISTANT,
                        MODEL,
                        0L,
                        new BigDecimal("5.00"),
                        new BigDecimal("4.99"),
                        new BigDecimal("0.02")));

        ConversationalAssistant.ChatResult result = assistant.chat(TENANT, null, "hi");

        assertThat(result.turn().content()).isEqualTo(ConversationalAssistant.COST_CAP_MESSAGE);
        assertThat(failureCount(AiAgentMetrics.AssistantFailureReason.COST_CAP)).isEqualTo(1);
    }

    @Test
    void emptyResponseTextReturnsSyntheticAndCountsParse() {
        AssistantConversation fresh = freshConversation("conv-6");
        when(repository.createNew(TENANT)).thenReturn(fresh);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean())).thenReturn(success("   "));

        ConversationalAssistant.ChatResult result = assistant.chat(TENANT, null, "hi");

        assertThat(result.turn().content()).isEqualTo(ConversationalAssistant.TRANSIENT_FAILURE_MESSAGE);
        verify(repository, never()).appendTurn(any(), any(), assistantTurnArg(), any());
        assertThat(failureCount(AiAgentMetrics.AssistantFailureReason.PARSE)).isEqualTo(1);
    }

    @Test
    void contextTruncationKeepsLastNTurnsAndAddsPreamble() {
        // 25 prior turns: 13 user + 12 assistant alternating.
        List<AssistantTurn> existingTurns = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            Instant ts = FIXED_CLOCK.instant().minusSeconds(60L * (25 - i));
            if (i % 2 == 0) {
                existingTurns.add(AssistantTurn.user("q" + i, ts));
            } else {
                existingTurns.add(AssistantTurn.assistant("a" + i, ts, BigDecimal.ZERO));
            }
        }
        AssistantConversation existing = new AssistantConversation(
                TENANT,
                "conv-long",
                FIXED_CLOCK.instant().minusSeconds(60L * 60),
                FIXED_CLOCK.instant().minusSeconds(60L),
                existingTurns,
                BigDecimal.ZERO);
        when(repository.findById(TENANT, "conv-long")).thenReturn(Optional.of(existing));
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean())).thenReturn(success("ok."));

        assistant.chat(TENANT, "conv-long", "newest question");

        ArgumentCaptor<AnthropicRequest> captor = ArgumentCaptor.forClass(AnthropicRequest.class);
        verify(anthropicClient).submit(captor.capture(), anyBoolean());
        AnthropicRequest req = captor.getValue();
        // 25 + 1 new user message = 26 in-memory turns; max=20 so we truncate.
        // Output: 1 preamble + 20 most recent = 21 messages total.
        assertThat(req.messages()).hasSize(21);
        assertThat(req.messages().get(0).content()).isEqualTo(ConversationalAssistant.TRUNCATED_PREAMBLE);
        // Last message must be the brand-new user message.
        assertThat(req.messages().get(req.messages().size() - 1).content()).isEqualTo("newest question");
    }

    @Test
    void contextTruncationOmittedWhenUnderLimit() {
        AssistantConversation existing = new AssistantConversation(
                TENANT,
                "conv-short",
                FIXED_CLOCK.instant().minusSeconds(60L * 5),
                FIXED_CLOCK.instant().minusSeconds(60L),
                List.of(
                        AssistantTurn.user("q1", FIXED_CLOCK.instant().minusSeconds(60L * 4)),
                        AssistantTurn.assistant("a1", FIXED_CLOCK.instant().minusSeconds(60L * 3), BigDecimal.ZERO)),
                BigDecimal.ZERO);
        when(repository.findById(TENANT, "conv-short")).thenReturn(Optional.of(existing));
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean())).thenReturn(success("ok."));

        assistant.chat(TENANT, "conv-short", "q2");

        ArgumentCaptor<AnthropicRequest> captor = ArgumentCaptor.forClass(AnthropicRequest.class);
        verify(anthropicClient).submit(captor.capture(), anyBoolean());
        AnthropicRequest req = captor.getValue();
        assertThat(req.messages()).hasSize(3);
        assertThat(req.messages().get(0).content()).isNotEqualTo(ConversationalAssistant.TRUNCATED_PREAMBLE);
    }

    @Test
    void systemPromptEnforcesReadOnly() {
        // The "READ-ONLY" clause is a CLAUDE.md guardrail #2 invariant.
        String prompt = assistant.systemPrompt();
        assertThat(prompt).contains("READ-ONLY");
        assertThat(prompt).contains("cannot place trades");
        assertThat(prompt).contains("0DTE SPY");
    }

    @Test
    void submittedRequestUsesAssistantRoleAndCorrectModel() {
        AssistantConversation fresh = freshConversation("conv-x");
        when(repository.createNew(TENANT)).thenReturn(fresh);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean())).thenReturn(success("ok."));

        assistant.chat(TENANT, null, "hi");

        ArgumentCaptor<AnthropicRequest> captor = ArgumentCaptor.forClass(AnthropicRequest.class);
        ArgumentCaptor<Boolean> retry = ArgumentCaptor.forClass(Boolean.class);
        verify(anthropicClient).submit(captor.capture(), retry.capture());
        AnthropicRequest req = captor.getValue();
        assertThat(req.role()).isEqualTo(Role.ASSISTANT);
        assertThat(req.model()).isEqualTo(MODEL);
        assertThat(req.maxTokens()).isEqualTo(ConversationalAssistant.MAX_OUTPUT_TOKENS);
        assertThat(req.temperature()).isEqualTo(0.3d);
        assertThat(req.tenantId()).isEqualTo(TENANT);
        assertThat(req.tools()).isEmpty();
        assertThat(retry.getValue()).isTrue();
    }

    @Test
    void successIncrementsTokenCounters() {
        AssistantConversation fresh = freshConversation("conv-tok");
        when(repository.createNew(TENANT)).thenReturn(fresh);
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean())).thenReturn(success("answer"));

        assistant.chat(TENANT, null, "hi");

        // success() helper supplies inputTokens=1500, outputTokens=50.
        assertThat(tokenCount("input")).isEqualTo(1500);
        assertThat(tokenCount("output")).isEqualTo(50);
    }

    @Test
    void blankUserMessageRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assistant.chat(TENANT, null, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankTenantRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assistant.chat("  ", null, "hi"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- helpers ----------

    private AssistantConversation freshConversation(String id) {
        Instant now = FIXED_CLOCK.instant();
        return new AssistantConversation(TENANT, id, now, now, List.of(), BigDecimal.ZERO);
    }

    private static AnthropicResponse.Success success(String text) {
        return new AnthropicResponse.Success(
                "req_ok",
                Role.ASSISTANT,
                MODEL,
                42L,
                text,
                List.of(),
                /* inputTokens */ 1500,
                /* outputTokens */ 50,
                /* cachedTokens */ 0,
                new BigDecimal("0.0150"));
    }

    private static AssistantTurn userTurnArg() {
        return argThat(new AnthropicTurnArgMatcher(AssistantTurn.ROLE_USER));
    }

    private static AssistantTurn assistantTurnArg() {
        return argThat(new AnthropicTurnArgMatcher(AssistantTurn.ROLE_ASSISTANT));
    }

    private double counterCount(String name) {
        Counter c = metrics.registry().find(name).counter();
        return c == null ? 0d : c.count();
    }

    private double failureCount(AiAgentMetrics.AssistantFailureReason reason) {
        Counter c = metrics.registry()
                .find(AiAgentMetrics.METER_ASSISTANT_FAILED)
                .tag("reason", reason.label())
                .counter();
        return c == null ? 0d : c.count();
    }

    private double tokenCount(String kind) {
        Counter c = metrics.registry()
                .find(AiAgentMetrics.METER_ASSISTANT_TOKENS)
                .tag("kind", kind)
                .counter();
        return c == null ? 0d : c.count();
    }

    /**
     * Matcher that checks the role of a captured {@link AssistantTurn}. We use
     * a simple {@link org.mockito.ArgumentMatcher} via a helper class so the
     * verify() call sites stay readable.
     */
    private static final class AnthropicTurnArgMatcher implements org.mockito.ArgumentMatcher<AssistantTurn> {
        private final String role;

        AnthropicTurnArgMatcher(String role) {
            this.role = role;
        }

        @Override
        public boolean matches(AssistantTurn argument) {
            return argument != null && role.equals(argument.role());
        }
    }
}
