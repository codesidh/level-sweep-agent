package com.levelsweep.aiagent.anthropic;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Inbound shape for {@link AnthropicClient#submit(AnthropicRequest)}. Carries
 * everything the hand-rolled HTTP client needs to:
 *
 * <ul>
 *   <li>Serialize a {@code POST /v1/messages} body</li>
 *   <li>Tag the call against the right {@link Role} cost-cap bucket</li>
 *   <li>Enforce per-tenant cost-cap pre-flight</li>
 *   <li>Audit the call into {@code audit_log.ai_calls} (architecture-spec §4.10)</li>
 * </ul>
 *
 * <p>{@code temperature} defaults to {@code 0} — replay-parity (architecture-spec
 * Principle #2) requires the same input → same output, so all production AI
 * calls must run deterministic. Tests can override but the production callers
 * (Sentinel/Narrator/Reviewer) rely on the default.
 *
 * <p>{@code maxInputCostEstimateUsd} is the caller's pre-flight estimate of
 * input + max-output cost. {@link AnthropicClient} compares this against the
 * remaining per-(tenant, role, day) budget BEFORE making the HTTP call.
 *
 * @param model            Anthropic model id, e.g. {@code claude-haiku-4-5}
 * @param systemPrompt     verbatim system prompt; cached when prompt-caching enabled
 * @param messages         user/assistant turns (most callers send a single user message)
 * @param tools            tool descriptors (empty for non-tool-use callers in S1)
 * @param maxTokens        cap on completion length
 * @param temperature      sampling temperature (default 0 for replay parity)
 * @param tenantId         per-tenant cost-cap + audit scope
 * @param role             Sentinel / Narrator / Assistant / Reviewer bucket
 * @param projectedCostUsd caller-supplied pre-flight cost estimate for cap check
 */
public record AnthropicRequest(
        String model,
        String systemPrompt,
        List<AnthropicMessage> messages,
        List<AnthropicTool> tools,
        int maxTokens,
        double temperature,
        String tenantId,
        Role role,
        BigDecimal projectedCostUsd) {

    /**
     * Anthropic agent role — used both as a logical bucket for cost caps and
     * as a configuration key into {@code anthropic.cost-cap-usd-per-tenant-per-day.*}.
     * Names match the keys in {@code application.yml}.
     */
    public enum Role {
        SENTINEL("sentinel"),
        NARRATOR("narrator"),
        ASSISTANT("assistant"),
        REVIEWER("reviewer");

        private final String configKey;

        Role(String configKey) {
            this.configKey = configKey;
        }

        public String configKey() {
            return configKey;
        }
    }

    public AnthropicRequest {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(projectedCostUsd, "projectedCostUsd");
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (temperature < 0.0d || temperature > 1.0d) {
            throw new IllegalArgumentException("temperature must be in [0, 1]");
        }
        if (projectedCostUsd.signum() < 0) {
            throw new IllegalArgumentException("projectedCostUsd must be non-negative");
        }
        // Defensive copies — the record is exposed by reference and the
        // serializer iterates the lists; downstream mutation would corrupt
        // both audit prompt-hash determinism and the on-the-wire body.
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
    }

    /**
     * Convenience for tests and simple callers: a non-tool-use message at
     * {@code temperature=0} with a single user message.
     */
    public static AnthropicRequest of(
            String model,
            String systemPrompt,
            String userMessage,
            int maxTokens,
            String tenantId,
            Role role,
            BigDecimal projectedCostUsd) {
        return new AnthropicRequest(
                model,
                systemPrompt,
                List.of(AnthropicMessage.user(userMessage)),
                List.of(),
                maxTokens,
                0.0d,
                tenantId,
                role,
                projectedCostUsd);
    }

    /** Optional; tools may be absent for plain narration / review prompts. */
    public Optional<List<AnthropicTool>> toolsOptional() {
        return tools.isEmpty() ? Optional.empty() : Optional.of(tools);
    }
}
