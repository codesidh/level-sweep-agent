package com.levelsweep.aiagent.assistant;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mongo persistence for {@link AssistantConversation} threads. Mirrors
 * {@link com.levelsweep.aiagent.narrator.TradeNarrativeRepository}'s pattern:
 * sync MongoClient via {@link Instance} for stub-mode dev/test, hand-rolled
 * Document mapping (the project pattern is not auto-mapped POJOs), index
 * created via {@link PostConstruct} but lazy-retried on failure.
 *
 * <p>Schema (BSON):
 *
 * <pre>
 *   { tenant_id        : "OWNER",
 *     conversation_id  : "9f86d081-...",
 *     created_at       : ISODate(...),
 *     updated_at       : ISODate(...),
 *     turns            : [ {role, content, ts, cost_usd}, ... ],
 *     total_cost_usd   : "0.0421" }
 * </pre>
 *
 * <p>Indexes:
 *
 * <ul>
 *   <li>{@code (tenant_id, conversation_id)} unique — primary lookup.</li>
 *   <li>{@code (tenant_id, updated_at desc)} — list "recent conversations for
 *       tenant" without a full collection scan.</li>
 * </ul>
 *
 * <p>{@code total_cost_usd} stored as a plain string (same convention as
 * {@code ai_calls.cost_usd}) so {@link BigDecimal} round-trips losslessly.
 *
 * <p>Best-effort writes — failures log + return so a transient Mongo blip
 * never produces a 500 on the chat path. The Assistant is advisory; a failed
 * persistence does not block the response.
 */
@ApplicationScoped
public class AssistantConversationRepository {

    private static final Logger LOG = LoggerFactory.getLogger(AssistantConversationRepository.class);

    private final Instance<MongoClient> mongoClientInstance;
    private final Clock clock;
    private final String databaseName;
    private final String collectionName;
    private final AtomicBoolean indexEnsured = new AtomicBoolean(false);

    @Inject
    public AssistantConversationRepository(
            Instance<MongoClient> mongoClientInstance,
            Clock clock,
            @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "level_sweep") String databaseName,
            @ConfigProperty(name = "assistant.conversations-collection", defaultValue = "assistant_conversations")
                    String collectionName) {
        this.mongoClientInstance = mongoClientInstance;
        this.clock = clock;
        this.databaseName = databaseName;
        this.collectionName = collectionName;
    }

    @PostConstruct
    void ensureIndexes() {
        // The actual index call is lazy (first write) so we don't crash on
        // boot when Mongo is unreachable in dev. But we still try here so
        // production cold-start emits the indexes ahead of the first write.
        tryEnsureIndex();
    }

    /** Look up a conversation by id. Empty when absent OR Mongo is unsatisfied. */
    public Optional<AssistantConversation> findById(String tenantId, String conversationId) {
        if (mongoClientInstance.isUnsatisfied()) {
            LOG.debug(
                    "assistant repository stub mode (findById) tenantId={} conversationId={}",
                    tenantId,
                    conversationId);
            return Optional.empty();
        }
        try {
            Document doc = collection()
                    .find(Filters.and(Filters.eq("tenant_id", tenantId), Filters.eq("conversation_id", conversationId)))
                    .first();
            return Optional.ofNullable(doc).map(AssistantConversationRepository::fromDocument);
        } catch (RuntimeException e) {
            LOG.warn(
                    "assistant_conversations findById failed tenantId={} conversationId={}: {}",
                    tenantId,
                    conversationId,
                    e.toString());
            return Optional.empty();
        }
    }

    /**
     * Create a fresh conversation row with no turns. Generates a UUID v4
     * server-side so callers cannot collide ids across tenants.
     */
    public AssistantConversation createNew(String tenantId) {
        Instant now = Instant.now(clock);
        AssistantConversation conv =
                new AssistantConversation(tenantId, UUID.randomUUID().toString(), now, now, List.of(), BigDecimal.ZERO);
        if (mongoClientInstance.isUnsatisfied()) {
            LOG.debug("assistant repository stub mode (createNew) tenantId={}", tenantId);
            return conv;
        }
        tryEnsureIndex();
        try {
            collection().insertOne(toDocument(conv));
        } catch (RuntimeException e) {
            LOG.warn("assistant_conversations createNew insert failed tenantId={}: {}", tenantId, e.toString());
        }
        return conv;
    }

    /**
     * Append one turn to an existing conversation and bump {@code updated_at}
     * + {@code total_cost_usd}. Upserts so a stub-mode-resumed conversation
     * lands cleanly when Mongo comes back.
     */
    public void appendTurn(String tenantId, String conversationId, AssistantTurn turn, BigDecimal cumulativeCost) {
        if (mongoClientInstance.isUnsatisfied()) {
            LOG.debug(
                    "assistant repository stub mode (appendTurn) tenantId={} conversationId={}",
                    tenantId,
                    conversationId);
            return;
        }
        tryEnsureIndex();
        try {
            collection()
                    .updateOne(
                            Filters.and(
                                    Filters.eq("tenant_id", tenantId), Filters.eq("conversation_id", conversationId)),
                            Updates.combine(
                                    Updates.push("turns", toTurnDocument(turn)),
                                    Updates.set("updated_at", Date.from(turn.ts())),
                                    Updates.set("total_cost_usd", cumulativeCost.toPlainString())));
        } catch (RuntimeException e) {
            LOG.warn(
                    "assistant_conversations appendTurn failed tenantId={} conversationId={}: {}",
                    tenantId,
                    conversationId,
                    e.toString());
        }
    }

    /** List recent conversations newest-first. Empty in stub mode. */
    public List<AssistantConversation> recentForTenant(String tenantId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        if (mongoClientInstance.isUnsatisfied()) {
            LOG.debug("assistant repository stub mode (recentForTenant) tenantId={}", tenantId);
            return List.of();
        }
        try {
            FindIterable<Document> docs = collection()
                    .find(Filters.eq("tenant_id", tenantId))
                    .sort(Sorts.descending("updated_at"))
                    .limit(limit);
            List<AssistantConversation> out = new ArrayList<>();
            for (Document d : docs) {
                out.add(fromDocument(d));
            }
            return List.copyOf(out);
        } catch (RuntimeException e) {
            LOG.warn("assistant_conversations recentForTenant failed tenantId={}: {}", tenantId, e.toString());
            return List.of();
        }
    }

    // ---------- mapping ----------

    /** Package-private: extracted for tests of document shape. */
    static Document toDocument(AssistantConversation conv) {
        Document d = new Document();
        d.put("tenant_id", conv.tenantId());
        d.put("conversation_id", conv.conversationId());
        d.put("created_at", Date.from(conv.createdAt()));
        d.put("updated_at", Date.from(conv.updatedAt()));
        List<Document> turnDocs = new ArrayList<>(conv.turns().size());
        for (AssistantTurn t : conv.turns()) {
            turnDocs.add(toTurnDocument(t));
        }
        d.put("turns", turnDocs);
        d.put("total_cost_usd", conv.totalCostUsd().toPlainString());
        return d;
    }

    static Document toTurnDocument(AssistantTurn turn) {
        return new Document("role", turn.role())
                .append("content", turn.content())
                .append("ts", Date.from(turn.ts()))
                .append("cost_usd", turn.costUsd().toPlainString());
    }

    @SuppressWarnings("unchecked")
    static AssistantConversation fromDocument(Document d) {
        List<Document> turnDocs = (List<Document>) d.getOrDefault("turns", List.<Document>of());
        List<AssistantTurn> turns = new ArrayList<>(turnDocs.size());
        for (Document td : turnDocs) {
            Date ts = td.getDate("ts");
            String costStr = td.getString("cost_usd");
            turns.add(new AssistantTurn(
                    td.getString("role"),
                    td.getString("content"),
                    ts == null ? Instant.EPOCH : ts.toInstant(),
                    costStr == null ? BigDecimal.ZERO : new BigDecimal(costStr)));
        }
        Date createdAt = d.getDate("created_at");
        Date updatedAt = d.getDate("updated_at");
        String totalCostStr = d.getString("total_cost_usd");
        return new AssistantConversation(
                d.getString("tenant_id"),
                d.getString("conversation_id"),
                createdAt == null ? Instant.EPOCH : createdAt.toInstant(),
                updatedAt == null ? Instant.EPOCH : updatedAt.toInstant(),
                turns,
                totalCostStr == null ? BigDecimal.ZERO : new BigDecimal(totalCostStr));
    }

    // ---------- internals ----------

    private MongoCollection<Document> collection() {
        return mongoClientInstance.get().getDatabase(databaseName).getCollection(collectionName);
    }

    private void tryEnsureIndex() {
        if (indexEnsured.get() || mongoClientInstance.isUnsatisfied()) {
            return;
        }
        if (!indexEnsured.compareAndSet(false, true)) {
            return;
        }
        try {
            MongoCollection<Document> coll = collection();
            coll.createIndex(
                    Indexes.ascending("tenant_id", "conversation_id"),
                    new IndexOptions().name("idx_tenant_conversation_unique").unique(true));
            coll.createIndex(
                    Indexes.compoundIndex(Indexes.ascending("tenant_id"), Indexes.descending("updated_at")),
                    new IndexOptions().name("idx_tenant_updated_at_desc"));
        } catch (RuntimeException e) {
            // Reset so a subsequent write can retry — Mongo might just be transiently down on cold-start.
            indexEnsured.set(false);
            LOG.warn("assistant_conversations index creation failed (will retry on next write): {}", e.toString());
        }
    }
}
