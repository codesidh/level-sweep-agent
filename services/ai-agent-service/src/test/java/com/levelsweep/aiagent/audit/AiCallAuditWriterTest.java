package com.levelsweep.aiagent.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.aiagent.anthropic.AnthropicMessage;
import com.levelsweep.aiagent.anthropic.AnthropicRequest;
import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
import com.levelsweep.aiagent.anthropic.AnthropicResponse;
import com.levelsweep.aiagent.anthropic.AnthropicTool;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import jakarta.enterprise.inject.Instance;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit-style tests for {@link AiCallAuditWriter}. Mockito over MongoClient.
 * Verifies:
 *
 * <ul>
 *   <li>Per-call summary is inserted into {@code ai_calls}</li>
 *   <li>Full prompt is inserted into {@code ai_prompts}, keyed by hash</li>
 *   <li>Failure variants emit a row with {@code outcome} field set</li>
 *   <li>Stub mode (no MongoClient) is a no-op</li>
 *   <li>Document field set matches architecture-spec §4.10</li>
 *   <li>Tenant id is preserved on every row</li>
 * </ul>
 */
class AiCallAuditWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-02T15:00:00Z"), ZoneOffset.UTC);

    @Test
    void toCallDocumentIncludesAllArchitectureSpec410Fields() {
        AnthropicRequest req = sentinelRequest();
        AnthropicResponse.Success success = new AnthropicResponse.Success(
                "req-1",
                Role.SENTINEL,
                "claude-haiku-4-5",
                250L,
                "ALLOW: clean",
                List.of("veto_signal"),
                3000,
                200,
                500,
                new BigDecimal("0.0042"));

        Document d = AiCallAuditWriter.toCallDocument(req, success, "hash-abc", "trace-xyz");

        // Every field listed in architecture-spec §4.10
        assertThat(d.getString("tenant_id")).isEqualTo("OWNER");
        assertThat(d.getString("role")).isEqualTo("sentinel");
        assertThat(d.getString("model")).isEqualTo("claude-haiku-4-5");
        assertThat(d.getString("prompt_hash")).isEqualTo("hash-abc");
        assertThat((List<?>) d.get("tool_calls")).isEqualTo(List.of("veto_signal"));
        assertThat(d.getString("response_text")).isEqualTo("ALLOW: clean");
        assertThat(d.getInteger("prompt_tokens")).isEqualTo(3000);
        assertThat(d.getInteger("completion_tokens")).isEqualTo(200);
        assertThat(d.getInteger("cached_tokens")).isEqualTo(500);
        assertThat(d.getLong("latency_ms")).isEqualTo(250L);
        assertThat(d.getString("cost_usd")).isEqualTo("0.0042");
        // cache_hit_ratio = cached / (input + cached) = 500 / 3500 ≈ 0.142857
        assertThat(d.getDouble("cache_hit_ratio")).isCloseTo(500.0 / 3500.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(d.getString("trace_id")).isEqualTo("trace-xyz");
        assertThat(d.getString("client_request_id")).isEqualTo("req-1");
        assertThat(d.getString("outcome")).isEqualTo("success");
    }

    @Test
    void toCallDocumentForFailureUsesZeroAccountingAndOutcomeLabel() {
        AnthropicRequest req = sentinelRequest();
        AnthropicResponse.RateLimited rl =
                new AnthropicResponse.RateLimited("req-1", Role.SENTINEL, "claude-haiku-4-5", 50L, "rate limited");

        Document d = AiCallAuditWriter.toCallDocument(req, rl, "hash-abc", "trace-xyz");

        assertThat(d.getString("outcome")).isEqualTo("rate_limited");
        assertThat(d.getInteger("prompt_tokens")).isZero();
        assertThat(d.getInteger("completion_tokens")).isZero();
        assertThat(d.getInteger("cached_tokens")).isZero();
        assertThat(d.getString("cost_usd")).isEqualTo("0");
        assertThat(d.getDouble("cache_hit_ratio")).isZero();
        assertThat(d.getLong("latency_ms")).isEqualTo(50L);
    }

    @Test
    void toCallDocumentForCostCapBreachUsesZeroAccountingAndCorrectOutcome() {
        AnthropicRequest req = sentinelRequest();
        AnthropicResponse.CostCapBreached b = new AnthropicResponse.CostCapBreached(
                "req-1",
                Role.SENTINEL,
                "claude-haiku-4-5",
                1L,
                new BigDecimal("1.00"),
                new BigDecimal("0.99"),
                new BigDecimal("0.10"));

        Document d = AiCallAuditWriter.toCallDocument(req, b, "hash-abc", "trace-xyz");

        assertThat(d.getString("outcome")).isEqualTo("cost_cap_breached");
    }

    @Test
    void toPromptDocumentCarriesFullPromptShape() throws Exception {
        AnthropicRequest req = sentinelRequest();
        Document d = AiCallAuditWriter.toPromptDocument(req, "hash-abc", CLOCK.instant());

        assertThat(d.getString("prompt_hash")).isEqualTo("hash-abc");
        assertThat(d.getString("tenant_id")).isEqualTo("OWNER");
        assertThat(d.getString("role")).isEqualTo("sentinel");
        assertThat(d.getString("model")).isEqualTo("claude-haiku-4-5");
        assertThat(d.getString("system_prompt")).isEqualTo("you are the sentinel");
        assertThat((List<?>) d.get("messages")).hasSize(1);
        assertThat((List<?>) d.get("tools")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordIsNoOpInStubMode() {
        Instance<MongoClient> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(true);
        AiCallAuditWriter writer = new AiCallAuditWriter(instance, CLOCK, "level_sweep", "ai_calls", "ai_prompts");

        // Must not throw.
        writer.record(sentinelRequest(), successResponse(), "trace-1");

        verify(instance, never()).get();
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordWritesPromptToColdCollectionThenSummary() {
        Instance<MongoClient> instance = mock(Instance.class);
        MongoClient client = mock(MongoClient.class);
        MongoDatabase db = mock(MongoDatabase.class);
        MongoCollection<Document> aiCalls = mock(MongoCollection.class);
        MongoCollection<Document> aiPrompts = mock(MongoCollection.class);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(client);
        when(client.getDatabase("level_sweep")).thenReturn(db);
        when(db.getCollection("ai_calls")).thenReturn(aiCalls);
        when(db.getCollection("ai_prompts")).thenReturn(aiPrompts);
        // Prompt does not exist yet — write it.
        when(aiPrompts.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        AiCallAuditWriter writer = new AiCallAuditWriter(instance, CLOCK, "level_sweep", "ai_calls", "ai_prompts");

        writer.record(sentinelRequest(), successResponse(), "trace-1");

        // Prompt collection got the cold blob.
        ArgumentCaptor<Document> promptCap = ArgumentCaptor.forClass(Document.class);
        verify(aiPrompts, times(1)).insertOne(promptCap.capture());
        assertThat(promptCap.getValue().getString("system_prompt")).isEqualTo("you are the sentinel");

        // Calls collection got the summary, NOT the full prompt body.
        ArgumentCaptor<Document> callCap = ArgumentCaptor.forClass(Document.class);
        verify(aiCalls, times(1)).insertOne(callCap.capture());
        Document callDoc = callCap.getValue();
        assertThat(callDoc.getString("response_text")).isEqualTo("ALLOW: clean");
        // Summary has the hash, not the full prompt body.
        assertThat(callDoc.containsKey("system_prompt")).isFalse();
        assertThat(callDoc.getString("prompt_hash")).isNotBlank();
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordSkipsPromptInsertWhenAlreadyPresent() {
        Instance<MongoClient> instance = mock(Instance.class);
        MongoClient client = mock(MongoClient.class);
        MongoDatabase db = mock(MongoDatabase.class);
        MongoCollection<Document> aiCalls = mock(MongoCollection.class);
        MongoCollection<Document> aiPrompts = mock(MongoCollection.class);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(client);
        when(client.getDatabase("level_sweep")).thenReturn(db);
        when(db.getCollection("ai_calls")).thenReturn(aiCalls);
        when(db.getCollection("ai_prompts")).thenReturn(aiPrompts);
        // Prompt already exists.
        when(aiPrompts.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(new Document("prompt_hash", "existing"));

        AiCallAuditWriter writer = new AiCallAuditWriter(instance, CLOCK, "level_sweep", "ai_calls", "ai_prompts");

        writer.record(sentinelRequest(), successResponse(), "trace-1");

        verify(aiPrompts, never()).insertOne(any(Document.class));
        verify(aiCalls, times(1)).insertOne(any(Document.class));
    }

    @Test
    void traceIdNullProducesEmptyString() {
        AnthropicRequest req = sentinelRequest();
        AnthropicResponse resp = successResponse();

        Document d = AiCallAuditWriter.toCallDocument(req, resp, "hash-abc", null);

        assertThat(d.getString("trace_id")).isEmpty();
    }

    @Test
    void cacheHitRatioIsZeroWhenNoTokens() {
        AnthropicRequest req = sentinelRequest();
        AnthropicResponse.Success success = new AnthropicResponse.Success(
                "req-1", Role.SENTINEL, "claude-haiku-4-5", 1L, "", List.of(), 0, 0, 0, BigDecimal.ZERO);

        Document d = AiCallAuditWriter.toCallDocument(req, success, "hash-abc", "trace");

        assertThat(d.getDouble("cache_hit_ratio")).isZero();
    }

    private static AnthropicRequest sentinelRequest() {
        try {
            JsonNode schema = MAPPER.readTree("{\"type\":\"object\",\"properties\":{}}");
            return new AnthropicRequest(
                    "claude-haiku-4-5",
                    "you are the sentinel",
                    List.of(AnthropicMessage.user("eval signal x")),
                    List.of(new AnthropicTool("veto_signal", "veto if confidence high", schema)),
                    300,
                    0.0d,
                    "OWNER",
                    Role.SENTINEL,
                    new BigDecimal("0.005"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static AnthropicResponse.Success successResponse() {
        return new AnthropicResponse.Success(
                "req-1",
                Role.SENTINEL,
                "claude-haiku-4-5",
                250L,
                "ALLOW: clean",
                List.of("veto_signal"),
                3000,
                200,
                500,
                new BigDecimal("0.0042"));
    }
}
