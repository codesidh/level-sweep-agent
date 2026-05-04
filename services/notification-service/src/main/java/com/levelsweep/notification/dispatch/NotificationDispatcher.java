package com.levelsweep.notification.dispatch;

import com.levelsweep.notification.outbox.NotificationOutboxRecord;
import com.levelsweep.notification.outbox.NotificationOutboxRepository;
import com.levelsweep.shared.domain.notification.NotificationEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Orchestrator for the Phase 6 fan-out. One {@link NotificationEvent} →
 * one or more channel attempts → one outbox row per channel attempt.
 *
 * <p>Channel routing matrix (architecture-spec §8 alerts hierarchy):
 *
 * <table>
 *   <caption>Severity → Channel routing</caption>
 *   <tr><th>Severity</th><th>Channels</th><th>Rationale</th></tr>
 *   <tr><td>INFO</td><td>email</td><td>Trade fills, info bulletins.</td></tr>
 *   <tr><td>WARN</td><td>email</td><td>CB open, soft halt.</td></tr>
 *   <tr><td>ERROR</td><td>email</td><td>Order rejected, AI error.</td></tr>
 *   <tr><td>CRITICAL</td><td>email + SMS</td><td>Risk halt, kill switch — must reach owner phone.</td></tr>
 * </table>
 *
 * <p>Idempotency: every dispatch attempt computes a deterministic
 * {@code sha256(tenantId + ":" + eventId)} dedupe key and tries the outbox
 * insert. The Mongo unique index on {@code (dedupe_key, channel)} catches
 * duplicate redeliveries — when {@code insertIfAbsent} returns
 * {@code false}, the dispatcher logs at INFO and short-circuits the
 * channel attempt itself, so a redelivery never sends a second email/SMS.
 *
 * <p>Order of operations (per channel):
 *
 * <ol>
 *   <li>Pre-dispatch outbox insert with status=SENT (optimistic) is
 *       intentionally NOT used; we don't want to claim "sent" before the
 *       send.</li>
 *   <li>Call the channel dispatcher; capture the outcome.</li>
 *   <li>Build the appropriate outbox record (SENT / FAILED / SKIPPED) and
 *       call {@link NotificationOutboxRepository#insertIfAbsent}.</li>
 *   <li>If the insert short-circuited (duplicate), log + skip — the prior
 *       attempt already recorded the outcome.</li>
 * </ol>
 *
 * <p>Failure semantics: a channel failure does NOT throw — we record
 * FAILED and move on to the next channel (or return). Spring Kafka thus
 * commits the offset on the listener's successful return; partial fan-out
 * (e.g., email succeeded, SMS failed) is captured in the outbox without
 * Kafka redelivering and re-sending the email. This is the AP delivery
 * profile per architecture-spec §6.
 */
@Component
public class NotificationDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final EmailDispatcher emailDispatcher;
    private final SmsDispatcher smsDispatcher;
    private final NotificationOutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public NotificationDispatcher(
            EmailDispatcher emailDispatcher, SmsDispatcher smsDispatcher, NotificationOutboxRepository outbox) {
        this(emailDispatcher, smsDispatcher, outbox, Clock.systemUTC());
    }

    /** Test-friendly constructor — fixed clock for deterministic attempted_at. */
    public NotificationDispatcher(
            EmailDispatcher emailDispatcher,
            SmsDispatcher smsDispatcher,
            NotificationOutboxRepository outbox,
            Clock clock) {
        this.emailDispatcher = Objects.requireNonNull(emailDispatcher, "emailDispatcher");
        this.smsDispatcher = Objects.requireNonNull(smsDispatcher, "smsDispatcher");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Dispatch one event to all enabled channels per the severity routing
     * matrix. Always attempts email; only attempts SMS when severity is
     * CRITICAL. Each channel attempt records exactly one outbox row.
     */
    public void dispatch(NotificationEvent event) {
        Objects.requireNonNull(event, "event");
        String dedupeKey = NotificationOutboxRepository.dedupeKey(event.tenantId(), event.eventId());
        // Audit-safe log line — no body.
        LOG.info(
                "dispatching tenant={} eventId={} severity={} title={} dedupeKey={}",
                event.tenantId(),
                event.eventId(),
                event.severity(),
                event.title(),
                dedupeKey);

        // Email — every severity ends up here.
        dispatchEmail(event, dedupeKey);

        // SMS — CRITICAL only. Phase 7 Twilio replaces the stub.
        if (event.severityEnum().fanOutAll()) {
            dispatchSms(event, dedupeKey);
        }
    }

    /** Drive the email channel and persist the outbox row. */
    private void dispatchEmail(NotificationEvent event, String dedupeKey) {
        Instant now = clock.instant();
        EmailDispatcher.Outcome outcome = emailDispatcher.dispatch(event);
        NotificationOutboxRecord row = toEmailRecord(event, dedupeKey, outcome, now);
        boolean inserted = outbox.insertIfAbsent(row);
        if (!inserted) {
            // Duplicate redelivery — the previous attempt already captured
            // the outcome. Log at INFO so ops can see the dedupe firing.
            LOG.info(
                    "email dispatch deduped tenant={} eventId={} dedupeKey={}",
                    event.tenantId(),
                    event.eventId(),
                    dedupeKey);
        }
    }

    /** Drive the SMS channel (Phase 7 stub) and persist the outbox row. */
    private void dispatchSms(NotificationEvent event, String dedupeKey) {
        Instant now = clock.instant();
        SmsDispatcher.Outcome outcome = smsDispatcher.dispatch(event);
        NotificationOutboxRecord row = toSmsRecord(event, dedupeKey, outcome, now);
        boolean inserted = outbox.insertIfAbsent(row);
        if (!inserted) {
            LOG.info(
                    "sms dispatch deduped tenant={} eventId={} dedupeKey={}",
                    event.tenantId(),
                    event.eventId(),
                    dedupeKey);
        }
    }

    /** Build the email outbox row from the channel outcome. */
    private static NotificationOutboxRecord toEmailRecord(
            NotificationEvent event, String dedupeKey, EmailDispatcher.Outcome outcome, Instant attemptedAt) {
        return switch (outcome.status()) {
            case SENT -> NotificationOutboxRecord.sent(
                    event.tenantId(),
                    dedupeKey,
                    event.eventId(),
                    NotificationOutboxRecord.Channel.EMAIL,
                    event.severity(),
                    event.title(),
                    attemptedAt,
                    event.correlationId());
            case FAILED -> NotificationOutboxRecord.failed(
                    event.tenantId(),
                    dedupeKey,
                    event.eventId(),
                    NotificationOutboxRecord.Channel.EMAIL,
                    event.severity(),
                    event.title(),
                    attemptedAt,
                    safeMessage(outcome.message()),
                    event.correlationId());
            case SKIPPED -> NotificationOutboxRecord.skipped(
                    event.tenantId(),
                    dedupeKey,
                    event.eventId(),
                    NotificationOutboxRecord.Channel.EMAIL,
                    event.severity(),
                    event.title(),
                    attemptedAt,
                    safeMessage(outcome.message()),
                    event.correlationId());
        };
    }

    /** Build the SMS outbox row from the channel outcome. */
    private static NotificationOutboxRecord toSmsRecord(
            NotificationEvent event, String dedupeKey, SmsDispatcher.Outcome outcome, Instant attemptedAt) {
        return switch (outcome.status()) {
            case SENT -> NotificationOutboxRecord.sent(
                    event.tenantId(),
                    dedupeKey,
                    event.eventId(),
                    NotificationOutboxRecord.Channel.SMS,
                    event.severity(),
                    event.title(),
                    attemptedAt,
                    event.correlationId());
            case FAILED -> NotificationOutboxRecord.failed(
                    event.tenantId(),
                    dedupeKey,
                    event.eventId(),
                    NotificationOutboxRecord.Channel.SMS,
                    event.severity(),
                    event.title(),
                    attemptedAt,
                    safeMessage(outcome.message()),
                    event.correlationId());
            case SKIPPED -> NotificationOutboxRecord.skipped(
                    event.tenantId(),
                    dedupeKey,
                    event.eventId(),
                    NotificationOutboxRecord.Channel.SMS,
                    event.severity(),
                    event.title(),
                    attemptedAt,
                    safeMessage(outcome.message()),
                    event.correlationId());
        };
    }

    /**
     * Channel outcomes for FAILED/SKIPPED carry a String message that may be
     * null; the outbox record contract permits null for {@code errorMessage}
     * but we substitute a placeholder to make the audit row self-explanatory
     * when the underlying provider didn't surface a cause.
     */
    private static String safeMessage(String raw) {
        return raw == null || raw.isBlank() ? "no_error_message" : raw;
    }
}
