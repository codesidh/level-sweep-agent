package com.levelsweep.journal.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;

/**
 * Immutable audit row landed in {@code audit_log.events} (architecture-spec
 * §13.2). One record per consumed Kafka event; the {@link #eventType} acts as
 * the discriminator (FILL, ORDER_SUBMITTED, ORDER_REJECTED, STOP_TRIGGERED,
 * TRAIL_BREACHED, EOD_FLATTENED, …). Single-collection design with a typed
 * {@code event_type} field is simpler than per-type collections and keeps the
 * query API ({@code GET /journal/{tenantId}?type=}) trivial.
 *
 * <p>Why not a Spring Data {@code @Document}: the schema is intentionally
 * polymorphic — {@link #payload} is the raw event Document so a future event
 * type can land without a class change. The {@link AuditWriter} pulls fields
 * out via {@link org.springframework.data.mongodb.core.MongoTemplate} rather
 * than a typed repository so we never drift between the on-the-wire JSON
 * shape and the persistence layer's view of the same payload.
 *
 * <p>Determinism: {@link #occurredAt} is the event's source time (e.g.
 * {@code TradeFilled.filledAt()}). {@link #writtenAt} is when this service
 * ingested the message — set at write time inside {@link AuditWriter}. The
 * journal trail must always have both: occurredAt for replay / lifecycle
 * stitching, writtenAt for ingest-lag SLO tracking.
 *
 * @param tenantId       multi-tenant key — every audit row is per-tenant
 *                       scoped; queries filter on this first index column
 * @param eventType      discriminator (e.g. FILL, ORDER_SUBMITTED). UPPER_SNAKE
 * @param sourceService  emitter (e.g. execution-service, decision-engine)
 * @param payload        raw event payload as a Mongo {@link Document}
 * @param occurredAt     event source time (NOT ingest time)
 * @param traceId        optional OpenTelemetry trace id for log/trace stitching
 * @param correlationId  optional saga correlation id for trade-lifetime stitching
 */
public record AuditRecord(
        String tenantId,
        String eventType,
        String sourceService,
        Document payload,
        Instant occurredAt,
        Optional<String> traceId,
        Optional<String> correlationId) {

    public AuditRecord {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(sourceService, "sourceService");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(correlationId, "correlationId");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (sourceService.isBlank()) {
            throw new IllegalArgumentException("sourceService must not be blank");
        }
    }

    /** Convenience factory for callers that don't have a trace context yet. */
    public static AuditRecord of(
            String tenantId, String eventType, String sourceService, Document payload, Instant occurredAt) {
        return new AuditRecord(
                tenantId, eventType, sourceService, payload, occurredAt, Optional.empty(), Optional.empty());
    }

    /** Convenience factory carrying a correlationId (saga stitching). */
    public static AuditRecord withCorrelation(
            String tenantId,
            String eventType,
            String sourceService,
            Document payload,
            Instant occurredAt,
            String correlationId) {
        return new AuditRecord(
                tenantId,
                eventType,
                sourceService,
                payload,
                occurredAt,
                Optional.empty(),
                Optional.ofNullable(correlationId));
    }
}
