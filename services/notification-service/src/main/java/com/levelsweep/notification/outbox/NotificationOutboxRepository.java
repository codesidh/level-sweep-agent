package com.levelsweep.notification.outbox;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/**
 * Mongo write-through for the {@code notifications.outbox} collection
 * (architecture-spec §13.2 — {@code notifications_log}). Append-only audit
 * of every channel dispatch attempt. Mirrors the
 * {@code journal-service.AuditWriter} pattern — raw {@link MongoTemplate}
 * access so the field shape stays first-class on the write path.
 *
 * <p>Indexes (created once at startup by {@link #ensureIndexes()}):
 *
 * <ul>
 *   <li>{@code (dedupe_key, channel)} — UNIQUE. Enforces idempotency:
 *       a redelivery of the same {@code (tenantId, eventId)} for the same
 *       channel triggers {@link DuplicateKeyException} and the dispatcher
 *       short-circuits. Two distinct channels (EMAIL + SMS) for one event
 *       are intentionally separate rows so per-channel delivery rates can
 *       be reasoned about independently.</li>
 *   <li>{@code (tenant_id, attempted_at DESC)} — query path for the
 *       (Phase 7) ops dashboard's "recent notifications by tenant" pane.
 *       Created here pre-emptively so the index is ready when the read API
 *       lands.</li>
 * </ul>
 *
 * <p>Failure mode: this is a cold-path service with AP delivery semantics.
 * The dispatcher writes the outbox row AFTER the channel attempt — if the
 * Mongo write fails, the channel dispatch itself already happened, but the
 * Kafka offset will not be committed (Spring Kafka commits only on
 * successful listener return), so the record is redelivered. The dedupe
 * unique index then catches the second dispatch attempt and the outbox
 * lands the SENT row exactly once.
 */
@Repository
public class NotificationOutboxRepository {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationOutboxRepository.class);

    /** Mongo collection name. */
    public static final String COLLECTION = "notifications.outbox";

    private final MongoTemplate mongo;

    public NotificationOutboxRepository(MongoTemplate mongo) {
        this.mongo = Objects.requireNonNull(mongo, "mongo");
    }

    /**
     * One-shot index creation. {@code @PostConstruct} runs once per pod
     * start; Mongo's {@code createIndex} is idempotent so a re-run is a
     * no-op. Keeping it here (not in a Mongo init script) means the
     * service is self-bootstrapping on a fresh dev cluster.
     */
    @PostConstruct
    public void ensureIndexes() {
        // Catch + log any index creation failure; don't kill the pod. A
        // missing unique index degrades to "duplicate rows possible on
        // redelivery" which is recoverable, while a startup crash on a
        // transient Mongo error is not.
        try {
            mongo.indexOps(COLLECTION)
                    .ensureIndex(new Index()
                            .on("dedupe_key", org.springframework.data.domain.Sort.Direction.ASC)
                            .on("channel", org.springframework.data.domain.Sort.Direction.ASC)
                            .unique()
                            .named("uniq_dedupe_channel"));
            mongo.indexOps(COLLECTION)
                    .ensureIndex(new Index()
                            .on("tenant_id", org.springframework.data.domain.Sort.Direction.ASC)
                            .on("attempted_at", org.springframework.data.domain.Sort.Direction.DESC)
                            .named("tenant_attempted_desc"));
            LOG.info("notifications.outbox indexes ensured");
        } catch (DataAccessException e) {
            LOG.warn(
                    "failed to ensure notifications.outbox indexes (continuing — duplicates possible until next restart)",
                    e);
        }
    }

    /**
     * Compute the deterministic dedupe key for an event. The key is a
     * lowercase hex sha256 of {@code tenantId + ":" + eventId} so two
     * different tenants accidentally producing the same {@code eventId}
     * get distinct rows.
     */
    public static String dedupeKey(String tenantId, String eventId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(eventId, "eventId");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((tenantId + ":" + eventId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated on every JRE; this is unreachable.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Persist one outbox row. Returns {@code true} if the row landed,
     * {@code false} if a {@link DuplicateKeyException} short-circuited the
     * insert (i.e., a duplicate redelivery — already dispatched). Any other
     * Mongo failure is rethrown so Spring Kafka redelivers and the next
     * attempt either succeeds or hits the dedupe.
     */
    public boolean insertIfAbsent(NotificationOutboxRecord record) {
        Objects.requireNonNull(record, "record");
        Document doc = toDocument(record);
        try {
            mongo.insert(doc, COLLECTION);
            return true;
        } catch (DuplicateKeyException e) {
            LOG.info(
                    "outbox dedupe — duplicate dispatch suppressed tenant={} eventId={} channel={}",
                    record.tenantId(),
                    record.eventId(),
                    record.channel());
            return false;
        } catch (DataAccessException e) {
            LOG.error(
                    "outbox insert failed tenant={} eventId={} channel={} status={} cause={}",
                    record.tenantId(),
                    record.eventId(),
                    record.channel(),
                    record.status(),
                    e.toString());
            throw e;
        }
    }

    /**
     * Test-friendly count of all rows for a tenant — used by integration
     * tests and the (Phase 7) dashboard. Always tenant-scoped per the
     * multi-tenant-readiness skill.
     */
    public long countForTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        return mongo.count(Query.query(Criteria.where("tenant_id").is(tenantId)), COLLECTION);
    }

    /**
     * Build the BSON document for a record. Package-private + side-effect-free
     * so unit tests can assert the wire shape without touching Mongo.
     */
    Document toDocument(NotificationOutboxRecord record) {
        Document doc = new Document()
                .append("tenant_id", record.tenantId())
                .append("dedupe_key", record.dedupeKey())
                .append("event_id", record.eventId())
                .append("channel", record.channel())
                .append("status", record.status())
                .append("severity", record.severity())
                .append("title", record.title())
                .append("attempted_at", record.attemptedAt());
        if (record.errorMessage() != null) {
            doc.append("error_message", record.errorMessage());
        }
        if (record.correlationId() != null) {
            doc.append("correlation_id", record.correlationId());
        }
        return doc;
    }
}
