package com.levelsweep.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Unit tests for {@link NotificationOutboxRepository}. Pure Mockito over
 * {@link MongoTemplate} — no real Mongo, no testcontainers.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Document shape — exact field names downstream consumers will rely on.</li>
 *   <li>Indexed-field presence — tenant_id, dedupe_key, channel must always
 *       be set so the unique + query indexes work.</li>
 *   <li>Idempotency — DuplicateKeyException returns false (short-circuit),
 *       never throws.</li>
 *   <li>Other DataAccessException — rethrown so Spring Kafka redelivers.</li>
 *   <li>dedupeKey() — deterministic sha256 of (tenantId + ":" + eventId).</li>
 *   <li>Optional fields (error_message, correlation_id) — omitted when null.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class NotificationOutboxRepositoryTest {

    @Mock
    private MongoTemplate mongo;

    private static final Instant FIXED = Instant.parse("2026-05-02T13:30:00Z");

    @Test
    void writesDocumentWithCanonicalFieldNames() {
        NotificationOutboxRepository repo = new NotificationOutboxRepository(mongo);
        NotificationOutboxRecord record = NotificationOutboxRecord.sent(
                "OWNER",
                "deadbeef",
                "evt-1",
                NotificationOutboxRecord.Channel.EMAIL,
                "WARN",
                "soft halt",
                FIXED,
                "corr-1");

        boolean inserted = repo.insertIfAbsent(record);

        assertThat(inserted).isTrue();
        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(mongo).insert(captor.capture(), eq("notifications.outbox"));
        Document doc = captor.getValue();
        // Indexed fields must always be present.
        assertThat(doc.getString("tenant_id")).isEqualTo("OWNER");
        assertThat(doc.getString("dedupe_key")).isEqualTo("deadbeef");
        assertThat(doc.getString("event_id")).isEqualTo("evt-1");
        assertThat(doc.getString("channel")).isEqualTo("EMAIL");
        assertThat(doc.getString("status")).isEqualTo("SENT");
        assertThat(doc.getString("severity")).isEqualTo("WARN");
        assertThat(doc.getString("title")).isEqualTo("soft halt");
        assertThat(doc.get("attempted_at", Instant.class)).isEqualTo(FIXED);
        assertThat(doc.getString("correlation_id")).isEqualTo("corr-1");
    }

    @Test
    void omitsOptionalFieldsWhenAbsent() {
        NotificationOutboxRepository repo = new NotificationOutboxRepository(mongo);
        // SENT row with no correlation id.
        NotificationOutboxRecord record = NotificationOutboxRecord.sent(
                "OWNER",
                "deadbeef",
                "evt-1",
                NotificationOutboxRecord.Channel.EMAIL,
                "INFO",
                "fill",
                FIXED,
                /* correlationId */ null);

        repo.insertIfAbsent(record);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(mongo).insert(captor.capture(), eq("notifications.outbox"));
        Document doc = captor.getValue();
        // Absent optional fields must NOT serialize as null — Mongo would
        // index a null and break the (tenant_id, attempted_at) index plan.
        assertThat(doc.containsKey("error_message")).isFalse();
        assertThat(doc.containsKey("correlation_id")).isFalse();
    }

    @Test
    void writesErrorMessageOnFailedRow() {
        NotificationOutboxRepository repo = new NotificationOutboxRepository(mongo);
        NotificationOutboxRecord record = NotificationOutboxRecord.failed(
                "OWNER",
                "deadbeef",
                "evt-1",
                NotificationOutboxRecord.Channel.EMAIL,
                "ERROR",
                "rejected",
                FIXED,
                "smtp 550 mailbox full",
                null);

        repo.insertIfAbsent(record);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(mongo).insert(captor.capture(), eq("notifications.outbox"));
        Document doc = captor.getValue();
        assertThat(doc.getString("status")).isEqualTo("FAILED");
        assertThat(doc.getString("error_message")).isEqualTo("smtp 550 mailbox full");
    }

    @Test
    void duplicateKeyExceptionReturnsFalseInsteadOfThrowing() {
        NotificationOutboxRepository repo = new NotificationOutboxRepository(mongo);
        doThrow(new DuplicateKeyException("dup")).when(mongo).insert(any(Document.class), eq("notifications.outbox"));
        NotificationOutboxRecord record = NotificationOutboxRecord.sent(
                "OWNER", "deadbeef", "evt-1", NotificationOutboxRecord.Channel.EMAIL, "INFO", "t", FIXED, null);

        boolean inserted = repo.insertIfAbsent(record);

        // Idempotency contract: the dedupe is expected behavior on Kafka
        // redelivery; the dispatcher needs a false return, not an exception.
        assertThat(inserted).isFalse();
    }

    @Test
    void otherDataAccessExceptionsAreRethrown() {
        NotificationOutboxRepository repo = new NotificationOutboxRepository(mongo);
        doThrow(new DataAccessResourceFailureException("mongo down"))
                .when(mongo)
                .insert(any(Document.class), eq("notifications.outbox"));
        NotificationOutboxRecord record = NotificationOutboxRecord.sent(
                "OWNER", "deadbeef", "evt-1", NotificationOutboxRecord.Channel.EMAIL, "INFO", "t", FIXED, null);

        // Mongo down is a transient infrastructure failure — Spring Kafka
        // must redeliver. Rethrow is the only correct stance.
        assertThatThrownBy(() -> repo.insertIfAbsent(record))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageContaining("mongo down");
    }

    @Test
    void dedupeKeyIsDeterministicSha256() {
        // Same (tenant, event) → same key. Different tenant → different key.
        // Different event → different key. Length is 64 hex chars.
        String key1 = NotificationOutboxRepository.dedupeKey("OWNER", "evt-1");
        String key2 = NotificationOutboxRepository.dedupeKey("OWNER", "evt-1");
        String key3 = NotificationOutboxRepository.dedupeKey("OTHER", "evt-1");
        String key4 = NotificationOutboxRepository.dedupeKey("OWNER", "evt-2");

        assertThat(key1).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(key1).isEqualTo(key2);
        assertThat(key1).isNotEqualTo(key3);
        assertThat(key1).isNotEqualTo(key4);
    }

    @Test
    void dedupeKeyRejectsNulls() {
        assertThatThrownBy(() -> NotificationOutboxRepository.dedupeKey(null, "e"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> NotificationOutboxRepository.dedupeKey("t", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void countForTenantRejectsBlank() {
        NotificationOutboxRepository repo = new NotificationOutboxRepository(mongo);
        assertThatThrownBy(() -> repo.countForTenant(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
        assertThatThrownBy(() -> repo.countForTenant(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordValidationRejectsBadInputs() {
        // Validation lives on NotificationOutboxRecord — the writer's contract
        // composes with it. Belt-and-braces sanity that bad inputs never reach
        // Mongo.
        assertThatThrownBy(() ->
                        new NotificationOutboxRecord("", "k", "e", "EMAIL", "SENT", "INFO", "t", FIXED, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
        assertThatThrownBy(() -> new NotificationOutboxRecord(
                        "OWNER", "k", "e", "BANANA", "SENT", "INFO", "t", FIXED, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
        assertThatThrownBy(() -> new NotificationOutboxRecord(
                        "OWNER", "k", "e", "EMAIL", "RANDOM", "INFO", "t", FIXED, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
        assertThatThrownBy(() -> new NotificationOutboxRecord(
                        "OWNER", "k", "e", "EMAIL", "SENT", "info", "t", FIXED, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UPPER_SNAKE");
    }
}
