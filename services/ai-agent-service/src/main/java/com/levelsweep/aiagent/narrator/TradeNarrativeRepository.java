package com.levelsweep.aiagent.narrator;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mongo persistence for {@link TradeNarrative} records. Mirrors
 * {@link com.levelsweep.aiagent.cost.DailyCostMongoRepository}'s pattern:
 * sync MongoClient, {@link Instance} wrapper for stub-mode dev/test, lazy
 * index creation on first write.
 *
 * <p>Schema (BSON):
 *
 * <pre>
 *   { tenant_id    : "OWNER",
 *     trade_id     : "TR_2026-05-02_001",
 *     event_type   : "FILL",
 *     narrative    : "Entry order filled at $1.42 ...",
 *     model_used   : "claude-sonnet-4-6",
 *     prompt_hash  : "9f86d081...",                 // SHA-256 hex
 *     generated_at : ISODate(...) }                  // UTC instant
 * </pre>
 *
 * <p>Index: {@code (tenant_id, trade_id, generated_at desc)} so the
 * Journal-Service / dashboard query "latest narratives for trade X" hits a
 * covered index. {@code generated_at desc} matches the typical UI sort order.
 *
 * <p>Best-effort writes — failures log + return so a transient Mongo blip
 * never blocks the AI hot path. The {@link TradeNarrator} is advisory; a
 * failed narrative write must NEVER block the trade FSM or the saga.
 */
@ApplicationScoped
public class TradeNarrativeRepository {

    private static final Logger LOG = LoggerFactory.getLogger(TradeNarrativeRepository.class);

    private final Instance<MongoClient> mongoClientInstance;
    private final String databaseName;
    private final String collectionName;
    private final AtomicBoolean indexEnsured = new AtomicBoolean(false);

    @Inject
    public TradeNarrativeRepository(
            Instance<MongoClient> mongoClientInstance,
            @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "level_sweep") String databaseName,
            @ConfigProperty(name = "narrator.trade-narratives-collection", defaultValue = "trade_narratives")
                    String collectionName) {
        this.mongoClientInstance = mongoClientInstance;
        this.databaseName = databaseName;
        this.collectionName = collectionName;
    }

    /**
     * Persist one narrative. Best-effort; logs + returns on Mongo failure
     * (callers — i.e. the listener — also swallow, by design).
     *
     * @param eventType the inbound event type from the listener — stored on
     *                  the narrative row for journal-side filtering. Not part
     *                  of the {@link TradeNarrative} record itself because the
     *                  prompt hash already encodes it via the prompt template.
     */
    public void save(TradeNarrative narrative, String eventType) {
        if (narrative == null) {
            return;
        }
        if (mongoClientInstance.isUnsatisfied()) {
            LOG.warn(
                    "trade_narratives repository running in stub mode — no MongoClient;"
                            + " tenantId={} tradeId={} eventType={}",
                    narrative.tenantId(),
                    narrative.tradeId(),
                    eventType);
            return;
        }
        ensureIndex();
        Document doc = toDocument(narrative, eventType);
        try {
            collection().insertOne(doc);
        } catch (RuntimeException e) {
            LOG.warn(
                    "trade_narratives insert failed tenantId={} tradeId={}: {}",
                    narrative.tenantId(),
                    narrative.tradeId(),
                    e.toString());
        }
    }

    /** Package-private: extracted for unit tests of document shape. */
    static Document toDocument(TradeNarrative narrative, String eventType) {
        Document d = new Document();
        d.put("tenant_id", narrative.tenantId());
        d.put("trade_id", narrative.tradeId());
        d.put("event_type", eventType == null ? "" : eventType);
        d.put("narrative", narrative.narrative());
        d.put("model_used", narrative.modelUsed());
        d.put("prompt_hash", narrative.promptHash());
        d.put("generated_at", Date.from(narrative.generatedAt()));
        return d;
    }

    private MongoCollection<Document> collection() {
        return mongoClientInstance.get().getDatabase(databaseName).getCollection(collectionName);
    }

    private void ensureIndex() {
        if (!indexEnsured.compareAndSet(false, true)) {
            return;
        }
        try {
            collection()
                    .createIndex(
                            Indexes.compoundIndex(
                                    Indexes.ascending("tenant_id", "trade_id"), Indexes.descending("generated_at")),
                            new IndexOptions().name("idx_tenant_trade_generated_at_desc"));
        } catch (RuntimeException e) {
            // Reset so the next write retries — Mongo might just be transiently down.
            indexEnsured.set(false);
            LOG.warn("trade_narratives index creation failed (will retry on next write): {}", e.toString());
        }
    }
}
