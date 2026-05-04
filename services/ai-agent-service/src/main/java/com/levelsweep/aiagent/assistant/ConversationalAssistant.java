package com.levelsweep.aiagent.assistant;

import com.levelsweep.aiagent.anthropic.AnthropicClient;
import com.levelsweep.aiagent.anthropic.AnthropicMessage;
import com.levelsweep.aiagent.anthropic.AnthropicRequest;
import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
import com.levelsweep.aiagent.anthropic.AnthropicResponse;
import com.levelsweep.aiagent.observability.AiAgentMetrics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Conversational Assistant orchestrator (architecture-spec §4.5 + ADR-0006).
 *
 * <p>The Assistant is the operator's chat interface: questions about already-
 * persisted state (trades, indicators, journal entries, the strategy). It is
 * READ-ONLY (CLAUDE.md guardrail #2 — the AI cannot place orders). The system
 * prompt explicitly enforces "you cannot place trades, modify config, or take
 * any action".
 *
 * <p>Contract:
 *
 * <ul>
 *   <li><b>Model</b>: {@code claude-sonnet-4-6}.</li>
 *   <li><b>Temperature</b>: 0.3 — bounded creativity for natural prose; the
 *       Assistant is not on the replay-parity path so deterministic 0.0 is
 *       not required.</li>
 *   <li><b>Output cap</b>: 1024 tokens (~4 sentences default).</li>
 *   <li><b>Context window</b>: last {@code assistant.context.max-turns} turns
 *       (default 20). Older turns are dropped with a "[earlier turns
 *       truncated]" preamble.</li>
 *   <li><b>Failure posture</b>: any non-Success outcome returns a synthetic
 *       assistant turn with a friendly retry message. The synthetic turn is
 *       NOT persisted (would pollute the conversation with errors).</li>
 *   <li><b>Cost cap</b>: {@link AnthropicClient} performs the hard pre-flight
 *       check; a CostCapBreached response surfaces a cap-specific error
 *       message but is otherwise treated like any other failure.</li>
 * </ul>
 *
 * <p>This orchestrator does NOT mutate trade / risk / FSM state and never
 * calls any tool. The Assistant's only persistent side-effect is appending
 * turns to its own conversation thread (architecture-spec §4.5 + §4.4).
 */
@ApplicationScoped
public class ConversationalAssistant {

    private static final Logger LOG = LoggerFactory.getLogger(ConversationalAssistant.class);

    /** ~4 sentence default; the prompt asks for ≤4 sentences. */
    public static final int MAX_OUTPUT_TOKENS = 1024;

    /**
     * Caller-supplied pre-flight cost projection. Sonnet 4.6 input ~$3/MTok,
     * output ~$15/MTok; ~2K in + 500 out → ~$0.0135. We project $0.02 as a
     * conservative upper bound — enough headroom that the cap rejects only
     * actual runaway spend, not normal turns.
     */
    static final BigDecimal PROJECTED_COST_USD = new BigDecimal("0.02");

    /** Friendly canned response for transient failures — operator can retry. */
    static final String TRANSIENT_FAILURE_MESSAGE =
            "I can't respond right now (transient failure). Please retry in a moment.";

    /** Cost-cap-specific message — distinguishes "retry later" from "retry tomorrow". */
    static final String COST_CAP_MESSAGE =
            "I've hit my daily cost cap and can't respond again until 00:00 ET. Please retry tomorrow.";

    /** Inserted at the head of the message list when older turns are dropped. */
    static final String TRUNCATED_PREAMBLE = "[earlier turns truncated]";

    private final AnthropicClient anthropicClient;
    private final AssistantConversationRepository repository;
    private final AiAgentMetrics metrics;
    private final Clock clock;
    private final String model;
    private final int maxContextTurns;
    private final String systemPromptVersion;

    @Inject
    public ConversationalAssistant(
            AnthropicClient anthropicClient,
            AssistantConversationRepository repository,
            AiAgentMetrics metrics,
            Clock clock,
            @ConfigProperty(name = "anthropic.models.assistant", defaultValue = "claude-sonnet-4-6") String model,
            @ConfigProperty(name = "assistant.context.max-turns", defaultValue = "20") int maxContextTurns,
            @ConfigProperty(name = "assistant.system-prompt-version", defaultValue = "v1") String systemPromptVersion) {
        this.anthropicClient = Objects.requireNonNull(anthropicClient, "anthropicClient");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.model = Objects.requireNonNull(model, "model");
        if (maxContextTurns <= 0) {
            throw new IllegalArgumentException("assistant.context.max-turns must be positive");
        }
        this.maxContextTurns = maxContextTurns;
        this.systemPromptVersion = Objects.requireNonNull(systemPromptVersion, "systemPromptVersion");
    }

    /**
     * One round-trip: append the user's question, call Anthropic, append the
     * model's response. Returns the resulting assistant turn.
     *
     * <p>If {@code conversationId} is null/blank, a new conversation is created
     * server-side (UUID v4). Callers can read the new id off the response in
     * the resource layer.
     */
    public ChatResult chat(String tenantId, String conversationId, String userMessage) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userMessage, "userMessage");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }

        // 1. Resolve or create the conversation.
        AssistantConversation conv = resolveConversation(tenantId, conversationId);

        // 2. Append the user turn — persisted before the model call so a crash
        //    mid-call still leaves a record of what was asked.
        Instant userTs = Instant.now(clock);
        AssistantTurn userTurn = AssistantTurn.user(userMessage, userTs);
        BigDecimal totalAfterUser = conv.totalCostUsd();
        repository.appendTurn(conv.tenantId(), conv.conversationId(), userTurn, totalAfterUser);
        List<AssistantTurn> rolling = appendInMemory(conv.turns(), userTurn);

        // 3. Build + submit the Anthropic request.
        AnthropicRequest aReq = buildRequest(conv.tenantId(), rolling);
        AnthropicResponse response = anthropicClient.submit(aReq, /* retryEnabled */ true);

        // 4. Branch on the variant.
        if (!(response instanceof AnthropicResponse.Success success)) {
            return failureTurn(conv, response);
        }
        String text =
                success.responseText() == null ? "" : success.responseText().trim();
        if (text.isEmpty()) {
            // Anthropic returned 200 with no content — same UX as transport
            // failure: synthetic message, no persist (don't poison the thread
            // with empty turns).
            LOG.warn(
                    "assistant received empty response text tenantId={} conversationId={}",
                    conv.tenantId(),
                    conv.conversationId());
            safeFailedMetric(conv.tenantId(), AiAgentMetrics.AssistantFailureReason.PARSE);
            return new ChatResult(
                    conv.conversationId(),
                    new AssistantTurn(
                            AssistantTurn.ROLE_ASSISTANT,
                            TRANSIENT_FAILURE_MESSAGE,
                            Instant.now(clock),
                            BigDecimal.ZERO));
        }

        // 5. Persist the assistant turn + bumped totals.
        Instant assistantTs = Instant.now(clock);
        AssistantTurn assistantTurn = AssistantTurn.assistant(text, assistantTs, success.costUsd());
        BigDecimal totalAfterAssistant = totalAfterUser.add(success.costUsd());
        repository.appendTurn(conv.tenantId(), conv.conversationId(), assistantTurn, totalAfterAssistant);

        // 6. Metrics.
        safeFiredMetric(conv.tenantId());
        safeTokensMetric(conv.tenantId(), "input", success.inputTokens());
        safeTokensMetric(conv.tenantId(), "output", success.outputTokens());

        return new ChatResult(conv.conversationId(), assistantTurn);
    }

    // ---------- helpers ----------

    private AssistantConversation resolveConversation(String tenantId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return repository.createNew(tenantId);
        }
        return repository.findById(tenantId, conversationId).orElseGet(() -> {
            // Caller passed an id we can't find — create a fresh thread server-side
            // and surface the new id back to them. Better than 404'ing on a
            // stale id from an old browser session.
            LOG.info(
                    "assistant unknown conversationId — creating new tenantId={} requestedId={}",
                    tenantId,
                    conversationId);
            return repository.createNew(tenantId);
        });
    }

    private static List<AssistantTurn> appendInMemory(List<AssistantTurn> existing, AssistantTurn next) {
        List<AssistantTurn> rolling = new ArrayList<>(existing.size() + 1);
        rolling.addAll(existing);
        rolling.add(next);
        return rolling;
    }

    /**
     * Build the Anthropic request with truncated context. The last
     * {@link #maxContextTurns} are kept; older turns are summarized as
     * {@link #TRUNCATED_PREAMBLE} prepended to the message list.
     */
    private AnthropicRequest buildRequest(String tenantId, List<AssistantTurn> turns) {
        List<AnthropicMessage> messages = new ArrayList<>();
        boolean truncated = turns.size() > maxContextTurns;
        if (truncated) {
            messages.add(AnthropicMessage.user(TRUNCATED_PREAMBLE));
        }
        int start = truncated ? turns.size() - maxContextTurns : 0;
        for (int i = start; i < turns.size(); i++) {
            AssistantTurn t = turns.get(i);
            if (AssistantTurn.ROLE_USER.equals(t.role())) {
                messages.add(AnthropicMessage.user(t.content()));
            } else {
                messages.add(AnthropicMessage.assistant(t.content()));
            }
        }
        return new AnthropicRequest(
                model,
                systemPrompt(),
                messages,
                List.of(),
                MAX_OUTPUT_TOKENS,
                /* temperature */ 0.3d,
                tenantId,
                Role.ASSISTANT,
                PROJECTED_COST_USD);
    }

    /**
     * Verbatim system prompt. The "you are READ-ONLY" clause is non-negotiable
     * — CLAUDE.md guardrail #2 (the AI cannot place orders) extends to the
     * Assistant. Any change to this string MUST bump
     * {@code assistant.system-prompt-version} for audit traceability.
     */
    String systemPrompt() {
        return "You are an AI conversational assistant for a 0DTE SPY options trader's operator. "
                + "You answer questions about trades, indicators, journal entries, and the strategy. "
                + "You are READ-ONLY — you cannot place trades, modify config, or take any action. "
                + "You may suggest queries the operator could run. "
                + "Be concise (≤4 sentences default) and reference specific trade IDs / dates when relevant.";
    }

    String systemPromptVersion() {
        return systemPromptVersion;
    }

    int maxContextTurns() {
        return maxContextTurns;
    }

    private ChatResult failureTurn(AssistantConversation conv, AnthropicResponse response) {
        AiAgentMetrics.AssistantFailureReason reason = reasonOf(response);
        LOG.warn(
                "assistant anthropic call non-success tenantId={} conversationId={} outcome={}",
                conv.tenantId(),
                conv.conversationId(),
                response.getClass().getSimpleName());
        safeFailedMetric(conv.tenantId(), reason);
        String message =
                reason == AiAgentMetrics.AssistantFailureReason.COST_CAP ? COST_CAP_MESSAGE : TRANSIENT_FAILURE_MESSAGE;
        // Synthetic turn — NOT persisted. The user turn was already saved so
        // the operator can see what they asked, but we don't pollute the
        // thread with error placeholders.
        return new ChatResult(
                conv.conversationId(),
                new AssistantTurn(AssistantTurn.ROLE_ASSISTANT, message, Instant.now(clock), BigDecimal.ZERO));
    }

    private static AiAgentMetrics.AssistantFailureReason reasonOf(AnthropicResponse response) {
        return switch (response) {
            case AnthropicResponse.Success ignored -> AiAgentMetrics.AssistantFailureReason.PARSE;
            case AnthropicResponse.RateLimited ignored -> AiAgentMetrics.AssistantFailureReason.ANTHROPIC_FAILURE;
            case AnthropicResponse.Overloaded ignored -> AiAgentMetrics.AssistantFailureReason.ANTHROPIC_FAILURE;
            case AnthropicResponse.InvalidRequest ignored -> AiAgentMetrics.AssistantFailureReason.ANTHROPIC_FAILURE;
            case AnthropicResponse.TransportFailure ignored -> AiAgentMetrics.AssistantFailureReason.TIMEOUT;
            case AnthropicResponse.CostCapBreached ignored -> AiAgentMetrics.AssistantFailureReason.COST_CAP;
        };
    }

    private void safeFailedMetric(String tenantId, AiAgentMetrics.AssistantFailureReason reason) {
        try {
            metrics.assistantFailed(tenantId, reason);
        } catch (RuntimeException e) {
            LOG.warn("assistant metrics emit failed (failed/{}): {}", reason, e.toString());
        }
    }

    private void safeFiredMetric(String tenantId) {
        try {
            metrics.assistantFired(tenantId);
        } catch (RuntimeException e) {
            LOG.warn("assistant metrics emit failed (fired): {}", e.toString());
        }
    }

    private void safeTokensMetric(String tenantId, String kind, int count) {
        try {
            metrics.assistantTokens(tenantId, kind, count);
        } catch (RuntimeException e) {
            LOG.warn("assistant metrics emit failed (tokens/{}): {}", kind, e.toString());
        }
    }

    /** Returned by {@link #chat}: carries both the new turn and the canonical conversation id. */
    public record ChatResult(String conversationId, AssistantTurn turn) {
        public ChatResult {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(turn, "turn");
        }
    }
}
