package com.levelsweep.aiagent.audit;

import com.levelsweep.aiagent.anthropic.AnthropicRequest;
import com.levelsweep.aiagent.anthropic.AnthropicResponse;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes per-AI-call audit rows to Mongo per architecture-spec §4.10.
 *
 * <p>Two collections (both under {@code audit_log}):
 *
 * <ul>
 *   <li>{@code ai_calls} — compact per-call summary: tenant, role, model,
 *       prompt hash, tool calls, response text, token + cost accounting,
 *       latency, cache-hit ratio, occurred_at, trace_id. One row per call,
 *       inserted on every variant of {@link AnthropicResponse} (Success +
 *       failure modes — failure rows have zero tokens and zero cost but the
 *       row is still emitted so the audit trail is complete).</li>
 *   <li>{@code ai_prompts} — full prompt content keyed by prompt hash. Phase 4
 *       uses Mongo for dev simplicity; architecture-spec §4.10 calls for
 *       "cold blob storage" — a Phase 7 migration moves these to Azure Blob
 *       (tracked in {@code architecture-spec.md} §22 / docs/runbooks
 *       Phase 7 follow-up). The prompt-hash + cold-store split keeps
 *       {@code ai_calls} small while preserving full reproducibility for
 *       reviewer / regulator inspection.</li>
 * </ul>
 *
 * <p>Best-effort writes — failures log + return so a transient Mongo blip
 * never blocks the AI hot path. Idempotency: the prompt collection inserts
 * are dedup'd by hash check before write so rerunning the same prompt does
 * not bloat the cold store.
 */
@ApplicationScoped
public class AiCallAuditWriter {

    private static final Logger LOG = LoggerFactory.getLogger(AiCallAuditWriter.class);

    private final Instance<MongoClient> mongoClientInstance;
    private final Clock clock;
    private final String databaseName;
    private final String aiCallsCollection;
    private final String aiPromptsCollection;

    @Inject
    public AiCallAuditWriter(
            Instance<MongoClient> mongoClientInstance,
            Clock clock,
            @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "level_sweep") String databaseName,
            @ConfigProperty(name = "audit.ai-calls-collection", defaultValue = "ai_calls") String aiCallsCollection,
            @ConfigProperty(name = "audit.ai-prompts-collection", defaultValue = "ai_prompts")
                    String aiPromptsCollection) {
        this.mongoClientInstance = mongoClientInstance;
        this.clock = clock;
        this.databaseName = databaseName;
        this.aiCallsCollection = aiCallsCollection;
        this.aiPromptsCollection = aiPromptsCollection;
    }

    /**
     * Persist a per-call audit row + (if not already present) the full prompt
     * to the cold collection. {@code traceId} is propagated from the upstream
     * trade-saga / chat-session trace and stored as-is for cross-collection
     * correlation in App Insights.
     */
    public void record(AnthropicRequest request, AnthropicResponse response, String traceId) {
        if (mongoClientInstance.isUnsatisfied()) {
            LOG.warn(
                    "ai-call audit writer running in stub mode — no MongoClient; tenantId={} role={} model={}",
                    request.tenantId(),
                    request.role(),
                    request.model());
            return;
        }
        String promptHash = PromptHasher.hash(request);
        try {
            Instant now = Instant.now(clock);
            // 1. Cold store — the full prompt body, keyed by hash.
            persistFullPromptIfAbsent(request, promptHash, now);
            // 2. Hot store — the call summary.
            collection(aiCallsCollection).insertOne(toCallDocument(request, response, promptHash, traceId, now));
        } catch (RuntimeException e) {
            LOG.warn(
                    "ai-call audit insert failed tenantId={} role={} model={}: {}",
                    request.tenantId(),
                    request.role(),
                    request.model(),
                    e.toString());
        }
    }

    /**
     * Insert one row in {@code ai_prompts} per unique hash. Subsequent calls
     * with the same hash skip the write so the cold collection stays compact.
     */
    private void persistFullPromptIfAbsent(AnthropicRequest request, String promptHash, Instant occurredAt) {
        MongoCollection<Document> coll = collection(aiPromptsCollection);
        Document existing = coll.find(new Document("prompt_hash", promptHash)).first();
        if (existing != null) {
            return;
        }
        coll.insertOne(toPromptDocument(request, promptHash, occurredAt));
    }

    /**
     * Package-private: extracted for unit tests of document shape. The
     * {@code occurredAt} parameter is the timestamp to record on the row —
     * production callers pass {@code Instant.now(clock)}; tests pin the value
     * for deterministic assertions.
     */
    static Document toCallDocument(
            AnthropicRequest request,
            AnthropicResponse response,
            String promptHash,
            String traceId,
            Instant occurredAt) {
        Document d = new Document();
        d.put("tenant_id", request.tenantId());
        d.put("role", request.role().configKey());
        d.put("model", request.model());
        d.put("prompt_hash", promptHash);

        // Variant-aware fields — Success rows get full token + cost accounting;
        // failure rows fall back to zeros (so reads don't need null checks).
        if (response instanceof AnthropicResponse.Success success) {
            d.put("response_text", success.responseText());
            d.put("tool_calls", List.copyOf(success.toolCalls()));
            d.put("prompt_tokens", success.inputTokens());
            d.put("completion_tokens", success.outputTokens());
            d.put("cached_tokens", success.cachedTokens());
            d.put("latency_ms", success.latencyMs());
            d.put("cost_usd", success.costUsd().toPlainString());
            int totalInput = success.inputTokens() + success.cachedTokens();
            double cacheHitRatio = totalInput == 0 ? 0.0 : (double) success.cachedTokens() / totalInput;
            d.put("cache_hit_ratio", cacheHitRatio);
            d.put("outcome", "success");
        } else {
            d.put("response_text", "");
            d.put("tool_calls", List.<String>of());
            d.put("prompt_tokens", 0);
            d.put("completion_tokens", 0);
            d.put("cached_tokens", 0);
            d.put("latency_ms", response.latencyMs());
            d.put("cost_usd", "0");
            d.put("cache_hit_ratio", 0.0);
            d.put("outcome", outcomeLabel(response));
        }
        d.put("client_request_id", response.clientRequestId());
        d.put("occurred_at", Date.from(occurredAt));
        d.put("trace_id", traceId == null ? "" : traceId);
        return d;
    }

    /** Convenience overload for tests that don't care about the exact occurred_at. */
    static Document toCallDocument(
            AnthropicRequest request, AnthropicResponse response, String promptHash, String traceId) {
        return toCallDocument(request, response, promptHash, traceId, Instant.EPOCH);
    }

    /** Package-private: cold-store prompt document shape. */
    static Document toPromptDocument(AnthropicRequest request, String promptHash, Instant occurredAt) {
        Document d = new Document();
        d.put("prompt_hash", promptHash);
        d.put("tenant_id", request.tenantId());
        d.put("role", request.role().configKey());
        d.put("model", request.model());
        d.put("system_prompt", request.systemPrompt());
        // Messages stored as a list of {role, content} sub-documents — easier
        // to inspect than a flat concatenation, and trivial to round-trip if a
        // reviewer wants to replay a prompt.
        List<Document> messageDocs = request.messages().stream()
                .map(m -> new Document("role", m.role()).append("content", m.content()))
                .toList();
        d.put("messages", messageDocs);
        // Tools stored as {name, description, input_schema_json} — input_schema
        // serialized via JsonNode#toString() to keep it human-readable in the
        // shell.
        List<Document> toolDocs = request.tools().stream()
                .map(t -> new Document("name", t.name())
                        .append("description", t.description())
                        .append("input_schema_json", t.inputSchema().toString()))
                .toList();
        d.put("tools", toolDocs);
        d.put("first_seen_at", Date.from(occurredAt));
        return d;
    }

    private static String outcomeLabel(AnthropicResponse response) {
        return switch (response) {
            case AnthropicResponse.Success ignored -> "success";
            case AnthropicResponse.RateLimited ignored -> "rate_limited";
            case AnthropicResponse.Overloaded ignored -> "overloaded";
            case AnthropicResponse.InvalidRequest ignored -> "invalid_request";
            case AnthropicResponse.TransportFailure ignored -> "transport_failure";
            case AnthropicResponse.CostCapBreached ignored -> "cost_cap_breached";
        };
    }

    private MongoCollection<Document> collection(String name) {
        return mongoClientInstance.get().getDatabase(databaseName).getCollection(name);
    }
}
