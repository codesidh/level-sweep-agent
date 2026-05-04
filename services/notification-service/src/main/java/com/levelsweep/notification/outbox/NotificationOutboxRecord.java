package com.levelsweep.notification.outbox;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * One row per delivery attempt landed in the Mongo
 * {@code notifications.outbox} collection (architecture-spec §13.2 —
 * {@code notifications_log}). Append-only audit of every channel dispatch
 * the service performs.
 *
 * <p>Why a per-attempt row, not a per-event row: a CRITICAL severity event
 * fans out to multiple channels (email + SMS); each channel attempt is its
 * own row so we can independently reason about per-channel failure rates,
 * per-channel latency, and per-channel retry. Aggregating by
 * {@link #dedupeKey} reconstructs the per-event view at query time.
 *
 * <p>Idempotency: {@link #dedupeKey} is the per-event idempotency key —
 * {@code sha256(tenantId + ":" + eventId)}. The Mongo collection has a
 * unique index on the {@code (dedupe_key, channel)} pair so a redelivery
 * from Kafka triggers a {@code DuplicateKeyException} on the second
 * dispatch attempt for the same channel; the dispatcher catches it,
 * logs at INFO ("already dispatched"), and short-circuits.
 *
 * <p>Document shape (one per channel attempt):
 *
 * <pre>{@code
 * {
 *   _id:              ObjectId,              // Mongo-assigned
 *   tenant_id:        String,                // multi-tenant partition key
 *   dedupe_key:       String,                // sha256(tenantId + ":" + eventId)
 *   event_id:         String,                // producer-side event id
 *   channel:          String,                // EMAIL | SMS
 *   status:           String,                // SENT | FAILED | SKIPPED
 *   severity:         String,                // INFO | WARN | ERROR | CRITICAL
 *   title:            String,                // event title (subject line)
 *   attempted_at:     ISODate,               // when dispatcher tried
 *   error_message:    String? (optional),    // populated on FAILED
 *   correlation_id:   String? (optional)     // saga correlation id
 * }
 * }</pre>
 *
 * <p>PII stance: the event {@code body} is intentionally NOT persisted in
 * the outbox row. The body may contain trade detail (P/L, position size)
 * and the audit collection is queryable by ops; the title + severity +
 * status give us everything we need for delivery-rate dashboards without
 * exposing trade content. Phase 7 may add an opt-in body field once a
 * data-classification policy exists.
 *
 * @param tenantId       multi-tenant key, never blank
 * @param dedupeKey      {@code sha256(tenantId + ":" + eventId)} — never blank
 * @param eventId        producer-side event id, never blank
 * @param channel        delivery channel — see {@link Channel}
 * @param status         dispatch outcome — see {@link Status}
 * @param severity       UPPER_SNAKE severity (INFO | WARN | ERROR | CRITICAL)
 * @param title          event title (subject line); audit-safe
 * @param attemptedAt    when the dispatcher attempted the channel
 * @param errorMessage   non-null iff {@link #status} == {@link Status#FAILED}
 * @param correlationId  optional saga correlation id; nullable
 */
public record NotificationOutboxRecord(
        String tenantId,
        String dedupeKey,
        String eventId,
        String channel,
        String status,
        String severity,
        String title,
        Instant attemptedAt,
        String errorMessage,
        String correlationId) {

    /** Allowed channels. UPPER_SNAKE matches the audit-trail taxonomy. */
    public enum Channel {
        EMAIL,
        SMS;
    }

    /** Allowed dispatch outcomes. */
    public enum Status {
        /** Channel handed the message to the downstream provider successfully. */
        SENT,
        /** Channel rejected the send (SMTP error, network failure, etc.). */
        FAILED,
        /** Channel was disabled or stubbed (Phase 7 SMS, empty SMTP host, etc.). */
        SKIPPED;
    }

    public NotificationOutboxRecord {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dedupeKey, "dedupeKey");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(attemptedAt, "attemptedAt");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (dedupeKey.isBlank()) {
            throw new IllegalArgumentException("dedupeKey must not be blank");
        }
        if (eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        // Channel + Status validated against the enum — keeps the value-object
        // shape simple and lets the dispatcher build records via String fields
        // without forcing every caller to import the enums.
        try {
            Channel.valueOf(channel);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("channel must be one of " + java.util.Arrays.toString(Channel.values()));
        }
        try {
            Status.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status must be one of " + java.util.Arrays.toString(Status.values()));
        }
        // Severity is normalized at the NotificationEvent boundary; here we
        // only sanity-check it's UPPER_SNAKE so a future direct caller doesn't
        // accidentally land lowercase rows in the outbox.
        if (!severity.equals(severity.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("severity must be UPPER_SNAKE (was: " + severity + ")");
        }
    }

    /** Sent (no error) — most-common factory shape. */
    public static NotificationOutboxRecord sent(
            String tenantId,
            String dedupeKey,
            String eventId,
            Channel channel,
            String severity,
            String title,
            Instant attemptedAt,
            String correlationId) {
        return new NotificationOutboxRecord(
                tenantId,
                dedupeKey,
                eventId,
                channel.name(),
                Status.SENT.name(),
                severity,
                title,
                attemptedAt,
                null,
                correlationId);
    }

    /** Failed (with error message) — factory shape for FAILED rows. */
    public static NotificationOutboxRecord failed(
            String tenantId,
            String dedupeKey,
            String eventId,
            Channel channel,
            String severity,
            String title,
            Instant attemptedAt,
            String errorMessage,
            String correlationId) {
        return new NotificationOutboxRecord(
                tenantId,
                dedupeKey,
                eventId,
                channel.name(),
                Status.FAILED.name(),
                severity,
                title,
                attemptedAt,
                errorMessage,
                correlationId);
    }

    /** Skipped (channel disabled / stubbed) — factory shape for SKIPPED rows. */
    public static NotificationOutboxRecord skipped(
            String tenantId,
            String dedupeKey,
            String eventId,
            Channel channel,
            String severity,
            String title,
            Instant attemptedAt,
            String reason,
            String correlationId) {
        return new NotificationOutboxRecord(
                tenantId,
                dedupeKey,
                eventId,
                channel.name(),
                Status.SKIPPED.name(),
                severity,
                title,
                attemptedAt,
                reason,
                correlationId);
    }
}
