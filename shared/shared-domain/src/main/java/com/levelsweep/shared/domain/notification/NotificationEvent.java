package com.levelsweep.shared.domain.notification;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Domain payload of the Kafka {@code notifications} topic (architecture-spec
 * §12.1). Producer side is multi-service: the Decision Engine emits
 * risk/halt alerts, the Execution Service emits order rejection alerts, the
 * AI Agent Service emits Sentinel-veto / Daily Reviewer alerts, and so on.
 * Each producer keys the topic by {@code tenantId} so partition stickiness
 * preserves per-tenant ordering through the fan-out.
 *
 * <p>Consumer side: {@code notification-service} subscribes to the topic and
 * dispatches each event to the enabled delivery channels (email, SMS in
 * Phase 7). The notification service is the canonical writer of the
 * {@code notifications.outbox} Mongo collection — the audit trail for
 * every delivery attempt.
 *
 * <p>Idempotency: the {@code (tenantId, eventId)} pair must be globally
 * unique per-tenant. The notification service computes the dedupe key as
 * {@code sha256(tenantId + ":" + eventId)} and the outbox collection has a
 * unique index on it; redelivery from Kafka (consumer rebalance, replay,
 * etc.) lands a {@code DuplicateKeyException} on the second insert and the
 * dispatcher short-circuits.
 *
 * <p>Severity drives channel routing in the dispatcher (architecture-spec
 * §8 — alerts hierarchy):
 *
 * <ul>
 *   <li>{@link Severity#INFO} — email only (e.g., trade fill notification).</li>
 *   <li>{@link Severity#WARN} — email only (e.g., circuit breaker open).</li>
 *   <li>{@link Severity#ERROR} — email only (e.g., order rejected by Alpaca).</li>
 *   <li>{@link Severity#CRITICAL} — email + SMS (e.g., risk halt, kill switch).</li>
 * </ul>
 *
 * <p>PII: the {@link #body} field MAY contain trade detail (symbol, strike,
 * P/L) but MUST NOT contain account credentials. The notification service
 * does not log {@link #body} at INFO; only {@link #title}, {@link #tenantId},
 * and the dispatch outcome. Phase 7 hardens this with a Logback message
 * scrubber.
 *
 * @param tenantId      multi-tenant key, never blank
 * @param eventId       producer-side unique id (UUID, ULID, etc.); the
 *                      idempotency key with {@link #tenantId}
 * @param severity      {@code INFO | WARN | ERROR | CRITICAL} (UPPER_SNAKE)
 * @param title         short subject line for email / SMS preamble
 * @param body          full body text; may be plain text or markdown-lite
 * @param tags          arbitrary metadata (e.g. {@code trade_id},
 *                      {@code alpaca_order_id}); may be empty
 * @param occurredAt    producer-side event time (NOT consumer ingest time)
 * @param correlationId optional saga / request correlation id for trade-
 *                      lifetime stitching across services; nullable
 */
public record NotificationEvent(
        String tenantId,
        String eventId,
        String severity,
        String title,
        String body,
        Map<String, String> tags,
        Instant occurredAt,
        String correlationId) {

    /** Allowed severity values. UPPER_SNAKE matches the audit-trail taxonomy. */
    public enum Severity {
        INFO,
        WARN,
        ERROR,
        CRITICAL;

        /** True iff the dispatcher should fan out to ALL enabled channels. */
        public boolean fanOutAll() {
            return this == CRITICAL;
        }
    }

    private static final Set<String> SEVERITIES = Set.of("INFO", "WARN", "ERROR", "CRITICAL");

    public NotificationEvent {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        // severity validation — case-insensitive on the wire, but normalized
        // up-front so downstream consumers can compare with .equals(). Keeps
        // the dispatcher's switch simple.
        String normalized = severity.toUpperCase(Locale.ROOT);
        if (!SEVERITIES.contains(normalized)) {
            throw new IllegalArgumentException("severity must be one of " + SEVERITIES + " (was: " + severity + ")");
        }
        severity = normalized;
        // Tags is conceptually optional. Default to an empty unmodifiable
        // map so consumers can iterate without null checks. Defensive copy
        // because the producer may pass a mutable HashMap.
        if (tags == null) {
            tags = Collections.emptyMap();
        } else {
            tags = Collections.unmodifiableMap(new HashMap<>(tags));
        }
    }

    /** Strongly-typed view of the validated severity field. */
    public Severity severityEnum() {
        return Severity.valueOf(severity);
    }

    /**
     * Convenience factory carrying no correlation id and no tags. Most-common
     * shape for one-shot alerts; saga-correlated callers use the canonical
     * constructor directly.
     */
    public static NotificationEvent simple(
            String tenantId, String eventId, Severity severity, String title, String body, Instant occurredAt) {
        return new NotificationEvent(
                tenantId, eventId, severity.name(), title, body, Collections.emptyMap(), occurredAt, null);
    }
}
