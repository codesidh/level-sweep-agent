package com.levelsweep.aiagent.cost;

import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Indexes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mongo persistence for the per-(tenant, role, day) AI cost bucket. Two
 * responsibilities:
 *
 * <ul>
 *   <li><b>Append</b> a per-call cost row to {@code audit_log.daily_cost} (one
 *       row per Anthropic call) — survives JVM restarts so the cap is honored
 *       across deployments.</li>
 *   <li><b>Restart recovery</b>: sum existing rows for {@code (tenantId, role,
 *       date)} so {@link DailyCostTracker} rebuilds its in-memory accumulator
 *       on bootstrap.</li>
 * </ul>
 *
 * <p>Sync MongoClient (not reactive) — same choice as {@code MongoBarRepository}
 * in market-data-service. The cost write is off the trade hot path (Sentinel /
 * Narrator / Reviewer / Assistant — not the order-submission path), so blocking
 * I/O on the AI thread is acceptable and reactive plumbing would only add
 * complexity.
 *
 * <p>{@code Instance<MongoClient>} wraps the dependency so the
 * {@code %test} profile (which disables mongodb devservices) can boot the bean
 * without a live Mongo. In stub mode reads return zero (i.e. fresh bucket) and
 * writes log + return — the in-memory tracker still enforces the cap for the
 * lifetime of the JVM, which is correct for unit tests.
 *
 * <p>Schema (BSON):
 *
 * <pre>
 *   { tenant_id : "OWNER",
 *     role      : "sentinel",
 *     date      : "2026-05-02",        // ET local date as ISO string
 *     cost_usd  : "0.0042",             // BigDecimal#toPlainString
 *     occurred_at : ISODate(...) }      // UTC instant
 * </pre>
 *
 * <p>Indexed by {@code (tenant_id, role, date)} ascending for the recovery
 * sum query.
 */
@ApplicationScoped
public class DailyCostMongoRepository {

    private static final Logger LOG = LoggerFactory.getLogger(DailyCostMongoRepository.class);

    private final Instance<MongoClient> mongoClientInstance;
    private final String databaseName;
    private final String collectionName;
    private final AtomicBoolean indexEnsured = new AtomicBoolean(false);

    @Inject
    public DailyCostMongoRepository(
            Instance<MongoClient> mongoClientInstance,
            @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "level_sweep") String databaseName,
            @ConfigProperty(name = "audit.daily-cost-collection", defaultValue = "daily_cost") String collectionName) {
        this.mongoClientInstance = mongoClientInstance;
        this.databaseName = databaseName;
        this.collectionName = collectionName;
    }

    /** Append a single per-call cost row. Best-effort — failures log + return. */
    public void append(String tenantId, Role role, LocalDate date, BigDecimal costUsd, Instant occurredAt) {
        if (mongoClientInstance.isUnsatisfied()) {
            LOG.warn(
                    "daily cost repository running in stub mode — no MongoClient; tenantId={} role={} date={} costUsd={}",
                    tenantId,
                    role,
                    date,
                    costUsd);
            return;
        }
        ensureIndex();
        Document doc = toDocument(tenantId, role, date, costUsd, occurredAt);
        try {
            collection().insertOne(doc);
        } catch (RuntimeException e) {
            LOG.warn("daily_cost insert failed tenantId={} role={} date={}: {}", tenantId, role, date, e.toString());
        }
    }

    /**
     * Sum existing rows for {@code (tenantId, role, date)}. Returns
     * {@link BigDecimal#ZERO} when Mongo is absent (test/dev) or query errors —
     * the in-memory accumulator continues from zero and the cap will still be
     * enforced for the JVM lifetime.
     */
    public BigDecimal sumByDay(String tenantId, Role role, LocalDate date) {
        if (mongoClientInstance.isUnsatisfied()) {
            return BigDecimal.ZERO;
        }
        try {
            Document filter = new Document("tenant_id", tenantId)
                    .append("role", role.configKey())
                    .append("date", date.toString());
            BigDecimal total = BigDecimal.ZERO;
            for (Document d : collection().find(filter)) {
                String s = d.getString("cost_usd");
                if (s != null && !s.isBlank()) {
                    total = total.add(new BigDecimal(s));
                }
            }
            return total;
        } catch (RuntimeException e) {
            LOG.warn("daily_cost sum query failed tenantId={} role={} date={}: {}", tenantId, role, date, e.toString());
            return BigDecimal.ZERO;
        }
    }

    /** Package-private: extracted for unit tests of document shape. */
    static Document toDocument(String tenantId, Role role, LocalDate date, BigDecimal costUsd, Instant occurredAt) {
        Document d = new Document();
        d.put("tenant_id", tenantId);
        d.put("role", role.configKey());
        d.put("date", date.toString());
        // Plain-string preserves BigDecimal scale losslessly. Audit reads
        // round-trip back to BigDecimal via the same `new BigDecimal(s)` shape
        // used by MongoBarRepository.
        d.put("cost_usd", costUsd.toPlainString());
        d.put("occurred_at", Date.from(occurredAt));
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
            collection().createIndex(Indexes.ascending("tenant_id", "role", "date"));
        } catch (RuntimeException e) {
            // Reset so the next write retries — Mongo might just be transiently down.
            indexEnsured.set(false);
            LOG.warn("daily_cost index creation failed (will retry on next write): {}", e.toString());
        }
    }
}
