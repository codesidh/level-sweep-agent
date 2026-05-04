package com.levelsweep.journal.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Unit tests for {@link AuditWriter}. Pure Mockito over {@link MongoTemplate} —
 * no real Mongo, no testcontainers, no Spring context. Covers:
 *
 * <ul>
 *   <li>Document shape — the exact field names that downstream consumers (the
 *       dashboard's Trade Journal pane, ad-hoc operator queries) will rely on.</li>
 *   <li>Indexed-field presence — tenant_id, event_type, occurred_at must
 *       always be set so the AuditRepository indexes work.</li>
 *   <li>{@code written_at} sourced from the injected clock so replay tests
 *       can pin it.</li>
 *   <li>Rethrow on DataAccessException — we must not silently drop audit rows.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AuditWriterTest {

    @Mock
    private MongoTemplate mongo;

    @Test
    void writesDocumentWithCanonicalFieldNames() {
        Instant occurred = Instant.parse("2026-05-02T13:30:00Z");
        Instant written = Instant.parse("2026-05-02T13:30:01Z");
        AuditWriter writer = new AuditWriter(mongo, Clock.fixed(written, ZoneOffset.UTC));
        Document payload = new Document("tradeId", "T1").append("filledQty", 1);
        AuditRecord record =
                AuditRecord.withCorrelation("OWNER", "FILL", "execution-service", payload, occurred, "corr-1");

        writer.write(record);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(mongo).insert(captor.capture(), eq("audit_log.events"));
        Document doc = captor.getValue();
        // Indexed fields per architecture-spec §13.2 — must always be present.
        assertThat(doc.getString("tenant_id")).isEqualTo("OWNER");
        assertThat(doc.getString("event_type")).isEqualTo("FILL");
        assertThat(doc.getString("source_service")).isEqualTo("execution-service");
        // Timestamp split: occurred_at = source time, written_at = ingest time.
        assertThat(doc.get("occurred_at", Instant.class)).isEqualTo(occurred);
        assertThat(doc.get("written_at", Instant.class)).isEqualTo(written);
        // Payload preserved as-is (polymorphic; no schema flattening).
        assertThat(doc.get("payload", Document.class)).isEqualTo(payload);
        // Correlation propagated for trade-lifetime stitching.
        assertThat(doc.getString("correlation_id")).isEqualTo("corr-1");
    }

    @Test
    void omitsOptionalFieldsWhenAbsent() {
        AuditWriter writer = new AuditWriter(mongo, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        AuditRecord record = AuditRecord.of(
                "OWNER", "ORDER_SUBMITTED", "execution-service", new Document(), Instant.parse("2026-05-02T13:30:00Z"));

        writer.write(record);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(mongo).insert(captor.capture(), eq("audit_log.events"));
        Document doc = captor.getValue();
        // Absent optional fields must NOT serialize as null — Mongo would
        // index a null and break the (tenant_id, correlation_id) index plan.
        assertThat(doc.containsKey("trace_id")).isFalse();
        assertThat(doc.containsKey("correlation_id")).isFalse();
    }

    @Test
    void uppercasesEventTypeStaysAsAuthored() {
        // Sanity: the writer never lowercases the event_type. The discriminator
        // taxonomy is UPPER_SNAKE (FILL, ORDER_SUBMITTED, ENTRY_COMPLETE, ...).
        AuditWriter writer = new AuditWriter(mongo, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        AuditRecord record =
                AuditRecord.of("OWNER", "ENTRY_COMPLETE", "decision-engine", new Document(), Instant.now());

        Document doc = writer.toDocument(record);
        assertThat(doc.getString("event_type")).isEqualTo("ENTRY_COMPLETE");
    }

    @Test
    void rethrowsDataAccessExceptionSoListenerRedelivers() {
        AuditWriter writer = new AuditWriter(mongo, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        AuditRecord record = AuditRecord.of(
                "OWNER", "FILL", "execution-service", new Document(), Instant.parse("2026-05-02T13:30:00Z"));

        doThrow(new DataAccessResourceFailureException("mongo down"))
                .when(mongo)
                .insert(any(Document.class), eq("audit_log.events"));

        // Rethrow is the contract — the journal cannot drop an audit row.
        // Spring Kafka's default error handler retries with backoff on throw.
        assertThatThrownBy(() -> writer.write(record))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageContaining("mongo down");
    }

    @Test
    void recordRequiresTenantIdEventTypeAndSourceService() {
        // Validation lives on AuditRecord (the record's compact constructor),
        // not the writer — but we sanity-check that the writer's contract
        // composes with it. A blank tenantId never reaches Mongo.
        Document payload = new Document();
        Instant now = Instant.parse("2026-05-02T13:30:00Z");

        assertThatThrownBy(() -> AuditRecord.of("", "FILL", "execution-service", payload, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
        assertThatThrownBy(() -> AuditRecord.of("OWNER", " ", "execution-service", payload, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
        assertThatThrownBy(() -> AuditRecord.of("OWNER", "FILL", "", payload, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceService");
    }

    @Test
    void recordOptionalsDefaultToEmpty() {
        AuditRecord record = AuditRecord.of(
                "OWNER", "FILL", "execution-service", new Document(), Instant.parse("2026-05-02T13:30:00Z"));
        assertThat(record.traceId()).isEqualTo(Optional.empty());
        assertThat(record.correlationId()).isEqualTo(Optional.empty());
    }
}
