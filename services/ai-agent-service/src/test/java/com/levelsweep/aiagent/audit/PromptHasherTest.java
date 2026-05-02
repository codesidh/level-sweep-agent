package com.levelsweep.aiagent.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.aiagent.anthropic.AnthropicMessage;
import com.levelsweep.aiagent.anthropic.AnthropicRequest;
import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
import com.levelsweep.aiagent.anthropic.AnthropicTool;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PromptHasher}. Verifies determinism, sensitivity to
 * inputs, and stability under no-op repackaging.
 */
class PromptHasherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void identicalRequestsHashIdentically() throws Exception {
        AnthropicRequest a = sentinelRequest("hello sentinel", List.of(toolFor("veto_signal")));
        AnthropicRequest b = sentinelRequest("hello sentinel", List.of(toolFor("veto_signal")));

        assertThat(PromptHasher.hash(a)).isEqualTo(PromptHasher.hash(b));
    }

    @Test
    void hashIsAlphanumericHex64Chars() throws Exception {
        String h = PromptHasher.hash(sentinelRequest("hello", List.of()));
        // SHA-256 hex = 64 chars.
        assertThat(h).hasSize(64);
        assertThat(h).matches("[0-9a-f]{64}");
    }

    @Test
    void differentSystemPromptsHashDifferently() throws Exception {
        AnthropicRequest a = sentinelRequest("hello", List.of());
        AnthropicRequest b = new AnthropicRequest(
                "claude-haiku-4-5",
                "DIFFERENT system prompt",
                List.of(AnthropicMessage.user("hello")),
                List.of(),
                300,
                0.0d,
                "OWNER",
                Role.SENTINEL,
                BigDecimal.ZERO);

        assertThat(PromptHasher.hash(a)).isNotEqualTo(PromptHasher.hash(b));
    }

    @Test
    void differentMessageContentHashesDifferently() throws Exception {
        AnthropicRequest a = sentinelRequest("hello sentinel", List.of());
        AnthropicRequest b = sentinelRequest("hello reviewer", List.of());

        assertThat(PromptHasher.hash(a)).isNotEqualTo(PromptHasher.hash(b));
    }

    @Test
    void differentModelHashesDifferently() throws Exception {
        AnthropicRequest a = sentinelRequest("hello", List.of());
        AnthropicRequest b = new AnthropicRequest(
                "claude-sonnet-4-6",
                a.systemPrompt(),
                a.messages(),
                a.tools(),
                a.maxTokens(),
                a.temperature(),
                a.tenantId(),
                a.role(),
                a.projectedCostUsd());

        assertThat(PromptHasher.hash(a)).isNotEqualTo(PromptHasher.hash(b));
    }

    @Test
    void messageOrderAffectsHash() throws Exception {
        AnthropicRequest a = new AnthropicRequest(
                "claude-haiku-4-5",
                "sys",
                List.of(AnthropicMessage.user("first"), AnthropicMessage.assistant("second")),
                List.of(),
                300,
                0.0d,
                "OWNER",
                Role.SENTINEL,
                BigDecimal.ZERO);
        AnthropicRequest b = new AnthropicRequest(
                "claude-haiku-4-5",
                "sys",
                List.of(AnthropicMessage.assistant("second"), AnthropicMessage.user("first")),
                List.of(),
                300,
                0.0d,
                "OWNER",
                Role.SENTINEL,
                BigDecimal.ZERO);

        assertThat(PromptHasher.hash(a)).isNotEqualTo(PromptHasher.hash(b));
    }

    @Test
    void differentToolSetsHashDifferently() throws Exception {
        AnthropicRequest a = sentinelRequest("hi", List.of(toolFor("veto_signal")));
        AnthropicRequest b = sentinelRequest("hi", List.of(toolFor("veto_signal"), toolFor("flag_for_review")));

        assertThat(PromptHasher.hash(a)).isNotEqualTo(PromptHasher.hash(b));
    }

    @Test
    void hashIgnoresFieldsNotPartOfCanonicalForm() throws Exception {
        // tenantId, role, maxTokens, temperature, projectedCostUsd are NOT
        // part of the canonical form — they are observability metadata, not
        // semantic prompt content. Two requests differing only in these fields
        // produce the same hash.
        AnthropicRequest a = sentinelRequest("hello", List.of());
        AnthropicRequest b = new AnthropicRequest(
                a.model(),
                a.systemPrompt(),
                a.messages(),
                a.tools(),
                a.maxTokens() + 1, // different
                a.temperature(),
                "OTHER", // different
                Role.NARRATOR, // different
                new BigDecimal("0.99")); // different

        assertThat(PromptHasher.hash(a)).isEqualTo(PromptHasher.hash(b));
    }

    @Test
    void rejectsNullRequest() {
        assertThatThrownBy(() -> PromptHasher.hash(null)).isInstanceOf(NullPointerException.class);
    }

    private static AnthropicRequest sentinelRequest(String userMsg, List<AnthropicTool> tools) {
        return new AnthropicRequest(
                "claude-haiku-4-5",
                "you are the sentinel",
                List.of(AnthropicMessage.user(userMsg)),
                tools,
                300,
                0.0d,
                "OWNER",
                Role.SENTINEL,
                BigDecimal.ZERO);
    }

    private static AnthropicTool toolFor(String name) {
        try {
            JsonNode schema = MAPPER.readTree("{\"type\":\"object\",\"properties\":{}}");
            return new AnthropicTool(name, "test tool " + name, schema);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
