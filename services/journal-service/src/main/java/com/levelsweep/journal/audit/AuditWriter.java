package com.levelsweep.journal.audit;

import java.time.Clock;
import java.util.Objects;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * Writes {@link AuditRecord} rows to the Mongo {@code audit_log.events}
 * collection (architecture-spec §13.2). Append-only. The collection is the
 * single source of truth for the audit trail surfaced via the dashboard's
 * Trade Journal pane (Phase 7).
 *
 * <p>Document shape (one per consumed event):
 *
 * <pre>{@code
 * {
 *   _id:            ObjectId,                  // Mongo-assigned
 *   tenant_id:      String,                    // multi-tenant partition key
 *   event_type:     String,                    // FILL, ORDER_SUBMITTED, ...
 *   source_service: String,                    // execution-service, decision-engine, ...
 *   payload:        Document,                  // raw event payload, schema-flexible
 *   occurred_at:    ISODate,                   // event source time (NOT ingest time)
 *   written_at:     ISODate,                   // ingest time, set here
 *   trace_id:       String? (optional),
 *   correlation_id: String? (optional)
 * }
 * }</pre>
 *
 * <p>Indexes (created once at startup by {@link #ensureIndexes()}):
 * <ul>
 *   <li>{@code (tenant_id, event_type, occurred_at DESC)} — primary query path
 *       for {@code GET /journal/{tenantId}?type=&from=&to=}.</li>
 *   <li>{@code (tenant_id, correlation_id)} — trade-lifetime stitching across
 *       signal evaluation → risk gate → order submit → fill chain.</li>
 * </ul>
 *
 * <p>Failure mode: this is a cold-path service. If the Mongo write fails the
 * upstream Kafka offset is NOT committed (Spring Kafka's default
 * {@code AckMode.BATCH} acks only on successful return), so the message is
 * redelivered on the next poll. The journal is the canonical audit trail —
 * we cannot drop a write — so we re-throw and let the consumer's
 * {@code SeekToCurrentErrorHandler} (Spring Kafka's default in 3.x) put us
 * into the retry → DLQ pipeline rather than swallow the error.
 *
 * <p>Why {@link MongoTemplate} not {@code MongoRepository}: the audit row's
 * {@code payload} is intentionally polymorphic (a raw {@link Document}). A
 * Spring Data repository would force a typed POJO + a per-event-type schema
 * dance; the template's {@code insert(Document)} keeps the wire shape and
 * the on-disk shape in lockstep. Mirrors the hand-rolled JDBC pattern used
 * by {@code execution-service}'s {@code EodFlattenAuditRepository}.
 */
@Service
public class AuditWriter {

    private static final Logger LOG = LoggerFactory.getLogger(AuditWriter.class);

    /** Single audit collection per architecture-spec §13.2 ("audit_log.events"). */
    public static final String COLLECTION = "audit_log.events";

    private final MongoTemplate mongo;
    private final Clock clock;

    @Autowired
    public AuditWriter(MongoTemplate mongo) {
        this(mongo, Clock.systemUTC());
    }

    /** Test-friendly constructor — inject a fixed clock for written_at assertions. */
    public AuditWriter(MongoTemplate mongo, Clock clock) {
        this.mongo = Objects.requireNonNull(mongo, "mongo");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Persist one audit record. Throws on Mongo failure — see class header
     * for redelivery semantics. The caller is the Kafka listener thread; we
     * intentionally do NOT catch {@link DataAccessException} here.
     */
    public void write(AuditRecord record) {
        Objects.requireNonNull(record, "record");
        Document doc = toDocument(record);
        try {
            mongo.insert(doc, COLLECTION);
        } catch (DataAccessException e) {
            LOG.error(
                    "audit insert failed tenant={} eventType={} sourceService={} cause={}",
                    record.tenantId(),
                    record.eventType(),
                    record.sourceService(),
                    e.toString());
            throw e;
        }
    }

    /**
     * Build the BSON document for a record. Package-private + side-effect-free
     * so unit tests can assert the wire shape without touching Mongo.
     */
    Document toDocument(AuditRecord record) {
        Document doc = new Document()
                .append("tenant_id", record.tenantId())
                .append("event_type", record.eventType())
                .append("source_service", record.sourceService())
                .append("payload", record.payload())
                .append("occurred_at", record.occurredAt())
                .append("written_at", clock.instant());
        record.traceId().ifPresent(t -> doc.append("trace_id", t));
        record.correlationId().ifPresent(c -> doc.append("correlation_id", c));
        return doc;
    }
}
