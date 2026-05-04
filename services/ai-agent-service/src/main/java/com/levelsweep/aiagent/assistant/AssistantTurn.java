package com.levelsweep.aiagent.assistant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Single turn in an {@link AssistantConversation}. Plain text per the Anthropic
 * Messages API content shape (see
 * {@link com.levelsweep.aiagent.anthropic.AnthropicMessage}). Tool-use turns
 * are not modeled here — the Assistant is read-only and does not invoke tools.
 *
 * @param role    {@value #ROLE_USER} or {@value #ROLE_ASSISTANT}
 * @param content the plain-text message body
 * @param ts      wall-clock instant the turn was recorded
 * @param costUsd the per-turn cost (zero for user turns; non-zero for
 *                successful assistant turns)
 */
public record AssistantTurn(String role, String content, Instant ts, BigDecimal costUsd) {

    /** Operator question. */
    public static final String ROLE_USER = "user";

    /** Model response. */
    public static final String ROLE_ASSISTANT = "assistant";

    public AssistantTurn {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(ts, "ts");
        Objects.requireNonNull(costUsd, "costUsd");
        if (!ROLE_USER.equals(role) && !ROLE_ASSISTANT.equals(role)) {
            throw new IllegalArgumentException("role must be 'user' or 'assistant', got: " + role);
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        if (costUsd.signum() < 0) {
            throw new IllegalArgumentException("costUsd must be non-negative");
        }
    }

    public static AssistantTurn user(String content, Instant ts) {
        return new AssistantTurn(ROLE_USER, content, ts, BigDecimal.ZERO);
    }

    public static AssistantTurn assistant(String content, Instant ts, BigDecimal costUsd) {
        return new AssistantTurn(ROLE_ASSISTANT, content, ts, costUsd);
    }
}
