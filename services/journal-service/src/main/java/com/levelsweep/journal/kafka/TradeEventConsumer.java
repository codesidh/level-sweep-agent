package com.levelsweep.journal.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.journal.audit.AuditRecord;
import com.levelsweep.journal.audit.AuditWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for the {@code tenant.events.*} topic family
 * (architecture-spec §12.1 — "All FSM transitions; basis for audit").
 *
 * <p><b>Topic-gap status (Phase 6):</b> the {@code tenant.events.*} producer
 * side does not exist yet. Phase 4 (ai-agent-service) noted this — the AI
 * Trade Narrator subscribes to {@code tenant.events.entry_complete} and
 * {@code tenant.events.exit_complete} but the producers in execution-service
 * + decision-engine are deferred to Phase 5/6 follow-up PRs:
 *
 * <ul>
 *   <li>{@code tenant.events.entry_complete} — emitted by execution-service
 *       when an entry order's bracket-open lands fully filled. Producer-side
 *       code lands in the Phase 5 stop-watcher / trail-manager PR chain.</li>
 *   <li>{@code tenant.events.exit_complete} — emitted by execution-service
 *       on bracket-close (stop hit, trail hit, or EOD flatten). Same
 *       producer-side PR chain.</li>
 *   <li>{@code tenant.events.signal_evaluated} — emitted by decision-engine
 *       on every 15-minute signal evaluation. Producer-side lands when
 *       decision-engine's signal evaluator wires its Kafka publisher
 *       (Phase 5 follow-up).</li>
 * </ul>
 *
 * <p>This consumer is wired with the right {@code @KafkaListener} annotations
 * on the right topic NAMES today so that, once those producers ship, the
 * journal starts ingesting without any further change to this module. The
 * consumer is harmless before then — Kafka returns nothing for a topic
 * pattern with no producers, the listener thread idles, and no audit rows
 * are written.
 *
 * <p>Wire format: a generic {@link JsonNode} so the consumer is forward-
 * compatible with whatever payload shape the producers settle on. The audit
 * row carries the entire JSON tree as a Mongo {@link Document} — the journal
 * is the source of truth for the audit trail, not the typed-DTO contract.
 *
 * <p><b>Naming convention for {@code event_type}:</b> the topic name is
 * normalized to UPPER_SNAKE — {@code tenant.events.entry_complete} →
 * {@code ENTRY_COMPLETE}. Matches the existing FILL / ORDER_SUBMITTED
 * taxonomy used by {@link TradeFillConsumer}.
 */
@Component
public class TradeEventConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(TradeEventConsumer.class);

    /** Source service tag — generic since the producer side is multi-service. */
    static final String SOURCE_SERVICE_DEFAULT = "decision-engine";

    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public TradeEventConsumer(AuditWriter auditWriter, @Qualifier("journalObjectMapper") ObjectMapper objectMapper) {
        this(auditWriter, objectMapper, Clock.systemUTC());
    }

    /** Test-friendly constructor — fixed clock for deterministic occurredAt. */
    public TradeEventConsumer(AuditWriter auditWriter, ObjectMapper objectMapper, Clock clock) {
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * One listener method per known topic. The annotations are live; the
     * consumer thread will idle until producers ship. Adding a new
     * {@code tenant.events.*} topic later means: (a) add a method here, or
     * (b) widen the {@code topicPattern} below. We chose explicit method-per-
     * topic for grep-ability and clearer ops dashboards.
     */
    @KafkaListener(
            topics = "tenant.events.entry_complete",
            groupId = "journal-service",
            containerFactory = "jsonNodeKafkaListenerContainerFactory",
            autoStartup = "${journal.kafka.events.enabled:true}")
    public void onEntryComplete(JsonNode event) {
        ingest("ENTRY_COMPLETE", event);
    }

    @KafkaListener(
            topics = "tenant.events.exit_complete",
            groupId = "journal-service",
            containerFactory = "jsonNodeKafkaListenerContainerFactory",
            autoStartup = "${journal.kafka.events.enabled:true}")
    public void onExitComplete(JsonNode event) {
        ingest("EXIT_COMPLETE", event);
    }

    @KafkaListener(
            topics = "tenant.events.signal_evaluated",
            groupId = "journal-service",
            containerFactory = "jsonNodeKafkaListenerContainerFactory",
            autoStartup = "${journal.kafka.events.enabled:true}")
    public void onSignalEvaluated(JsonNode event) {
        ingest("SIGNAL_EVALUATED", event);
    }

    /**
     * Common path for every {@code tenant.events.*} listener — extract the
     * tenant id, build a record, and persist. Package-private so unit tests
     * can drive the path without going through Spring Kafka.
     */
    void ingest(String eventType, JsonNode event) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(event, "event");

        String tenantId = extractText(event, "tenantId");
        if (tenantId == null || tenantId.isBlank()) {
            // Without tenantId we cannot per-tenant scope the audit row. Log
            // and drop — the message offset still commits because we don't
            // re-throw; Spring Kafka treats a returned-null as success.
            LOG.warn(
                    "tenant.events.* event missing tenantId — dropping. eventType={} payloadKeys={}",
                    eventType,
                    event.fieldNames().hasNext() ? event.fieldNames().next() : "<empty>");
            return;
        }

        String correlationId = extractText(event, "correlationId");
        Instant occurredAt = extractInstant(event, "occurredAt").orElseGet(clock::instant);

        Document payload = jsonToDocument(event);
        AuditRecord record = AuditRecord.withCorrelation(
                tenantId, eventType, SOURCE_SERVICE_DEFAULT, payload, occurredAt, correlationId);

        LOG.info(
                "audit event tenant={} eventType={} correlationId={} occurredAt={}",
                tenantId,
                eventType,
                correlationId,
                occurredAt);
        auditWriter.write(record);
    }

    private static String extractText(JsonNode event, String field) {
        JsonNode node = event.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText(null);
    }

    private static java.util.Optional<Instant> extractInstant(JsonNode event, String field) {
        JsonNode node = event.get(field);
        if (node == null || node.isNull()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Instant.parse(node.asText()));
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Convert the JSON tree to a Mongo {@link Document}. Nested objects are
     * recursively re-wrapped as {@link Document} so callers (and the audit
     * query API) get a uniform BSON view back. Without the recursive wrap,
     * Jackson's {@code convertValue(..., Map.class)} produces nested
     * {@link java.util.LinkedHashMap}s — the Mongo driver still serializes
     * them correctly on the write path, but the read path's
     * {@code Document#get(field, Document.class)} would ClassCastException
     * because the in-memory tree is a Map, not a Document.
     */
    Document jsonToDocument(JsonNode event) {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> map = objectMapper.convertValue(event, java.util.Map.class);
        return wrapMap(map);
    }

    @SuppressWarnings("unchecked")
    private static Document wrapMap(java.util.Map<String, Object> map) {
        Document doc = new Document();
        for (java.util.Map.Entry<String, Object> e : map.entrySet()) {
            doc.append(e.getKey(), wrapValue(e.getValue()));
        }
        return doc;
    }

    @SuppressWarnings("unchecked")
    private static Object wrapValue(Object value) {
        if (value instanceof java.util.Map<?, ?> m) {
            return wrapMap((java.util.Map<String, Object>) m);
        }
        if (value instanceof java.util.List<?> list) {
            java.util.List<Object> out = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                out.add(wrapValue(item));
            }
            return out;
        }
        return value;
    }
}
