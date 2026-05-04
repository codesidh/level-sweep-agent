package com.levelsweep.journal.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.journal.audit.AuditRecord;
import com.levelsweep.journal.audit.AuditWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link TradeEventConsumer}. Verifies that the
 * gap-stubbed {@code tenant.events.*} ingest path:
 *
 * <ul>
 *   <li>Builds an audit record per topic, with the correct event_type
 *       discriminator (UPPER_SNAKE).</li>
 *   <li>Honors tenantId in the payload (multi-tenant scope).</li>
 *   <li>Falls back to {@code clock.instant()} when the event has no
 *       occurredAt — keeps the record persistable even before producers
 *       settle on a payload contract.</li>
 *   <li>Drops malformed (no-tenant) events with a WARN, never re-throws.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TradeEventConsumerTest {

    @Mock
    private AuditWriter auditWriter;

    // findAndRegisterModules() picks up Scala module from spring-kafka-test
    // transitive deps and crashes on the version mismatch. Register only
    // the JSR-310 module — the only one this consumer actually needs for
    // Instant parsing in the production path.
    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @Test
    void entryCompleteMapsToUpperSnakeEventType() throws Exception {
        TradeEventConsumer consumer =
                new TradeEventConsumer(auditWriter, objectMapper, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        JsonNode event = objectMapper.readTree(
                "{\"tenantId\":\"OWNER\",\"correlationId\":\"corr-9\",\"occurredAt\":\"2026-05-02T13:30:00Z\",\"tradeId\":\"T9\"}");

        consumer.onEntryComplete(event);

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditWriter).write(captor.capture());
        AuditRecord record = captor.getValue();
        assertThat(record.tenantId()).isEqualTo("OWNER");
        assertThat(record.eventType()).isEqualTo("ENTRY_COMPLETE");
        assertThat(record.sourceService()).isEqualTo("decision-engine");
        assertThat(record.correlationId()).contains("corr-9");
        assertThat(record.occurredAt()).isEqualTo(Instant.parse("2026-05-02T13:30:00Z"));
        // The full payload survives — operators want the full FSM-transition
        // detail in the audit trail.
        assertThat(record.payload().getString("tradeId")).isEqualTo("T9");
    }

    @Test
    void exitCompleteMapsToExitCompleteEventType() throws Exception {
        TradeEventConsumer consumer =
                new TradeEventConsumer(auditWriter, objectMapper, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        JsonNode event = objectMapper.readTree("{\"tenantId\":\"OWNER\"}");

        consumer.onExitComplete(event);

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditWriter).write(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("EXIT_COMPLETE");
    }

    @Test
    void signalEvaluatedMapsToSignalEvaluatedEventType() throws Exception {
        TradeEventConsumer consumer =
                new TradeEventConsumer(auditWriter, objectMapper, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        JsonNode event = objectMapper.readTree("{\"tenantId\":\"OWNER\"}");

        consumer.onSignalEvaluated(event);

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditWriter).write(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("SIGNAL_EVALUATED");
    }

    @Test
    void missingOccurredAtFallsBackToClock() throws Exception {
        Instant fallback = Instant.parse("2026-05-02T20:00:00Z");
        TradeEventConsumer consumer =
                new TradeEventConsumer(auditWriter, objectMapper, Clock.fixed(fallback, ZoneOffset.UTC));
        JsonNode event = objectMapper.readTree("{\"tenantId\":\"OWNER\"}");

        consumer.onEntryComplete(event);

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditWriter).write(captor.capture());
        // Producer hasn't shipped yet (gap) — when occurredAt is missing, the
        // consumer's injected clock fills the field. Replay uses a fixed
        // clock so this stays deterministic.
        assertThat(captor.getValue().occurredAt()).isEqualTo(fallback);
    }

    @Test
    void missingTenantIdDropsEventWithoutThrowing() throws Exception {
        TradeEventConsumer consumer =
                new TradeEventConsumer(auditWriter, objectMapper, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        JsonNode event = objectMapper.readTree("{\"correlationId\":\"corr-orphan\"}");

        // Must not throw — Spring Kafka would treat throw as redeliver, and
        // a tenantless event is unrecoverable; redelivery would just spin.
        consumer.ingest("ENTRY_COMPLETE", event);

        verify(auditWriter, never()).write(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void payloadConvertsNestedJsonToBsonDocument() throws Exception {
        TradeEventConsumer consumer =
                new TradeEventConsumer(auditWriter, objectMapper, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        JsonNode event = objectMapper.readTree(
                "{\"tenantId\":\"OWNER\",\"levels\":{\"pdh\":500.5,\"pdl\":495.0},\"tags\":[\"a\",\"b\"]}");

        consumer.onSignalEvaluated(event);

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditWriter).write(captor.capture());
        org.bson.Document payload = captor.getValue().payload();
        // Nested objects survive as Documents; arrays survive as Lists.
        org.bson.Document levels = payload.get("levels", org.bson.Document.class);
        assertThat(levels).isNotNull();
        assertThat(levels.getDouble("pdh")).isEqualTo(500.5);
        assertThat(payload.get("tags")).isInstanceOf(java.util.List.class);
    }
}
