package com.levelsweep.aiagent.anthropic;

import java.util.Objects;

/**
 * Single conversational turn in the Anthropic Messages API request envelope.
 *
 * <p>Per the Anthropic REST contract (architecture-spec §4 + ADR-0006), every
 * {@code messages[]} entry carries a {@code role} of either {@code "user"} or
 * {@code "assistant"}; the {@code system} prompt is supplied separately on
 * {@link AnthropicRequest}. A multi-turn Sentinel/Assistant conversation is
 * just an ordered list of these records.
 *
 * <p>Tool-use turns ({@code tool_use} / {@code tool_result} content blocks)
 * land in S5 (Sentinel veto loop) with a richer content shape; for S1 the
 * record carries plain text and the request serializer wraps it as
 * {@code [{"type":"text","text":"..."}]} on the wire.
 */
public record AnthropicMessage(String role, String content) {

    /** {@code role = "user"} message — most prompts originate as one of these. */
    public static final String ROLE_USER = "user";

    /** {@code role = "assistant"} — model turn replayed on a follow-up call. */
    public static final String ROLE_ASSISTANT = "assistant";

    public AnthropicMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        if (!ROLE_USER.equals(role) && !ROLE_ASSISTANT.equals(role)) {
            throw new IllegalArgumentException("role must be 'user' or 'assistant', got: " + role);
        }
    }

    public static AnthropicMessage user(String content) {
        return new AnthropicMessage(ROLE_USER, content);
    }

    public static AnthropicMessage assistant(String content) {
        return new AnthropicMessage(ROLE_ASSISTANT, content);
    }
}
