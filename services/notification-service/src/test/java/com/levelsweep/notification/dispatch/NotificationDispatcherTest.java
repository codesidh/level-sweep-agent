package com.levelsweep.notification.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.levelsweep.notification.outbox.NotificationOutboxRecord;
import com.levelsweep.notification.outbox.NotificationOutboxRepository;
import com.levelsweep.shared.domain.notification.NotificationEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link NotificationDispatcher}. Severity-routing matrix is
 * the central contract — INFO/WARN/ERROR end up email-only; CRITICAL fans
 * out to email + SMS. Each channel attempt produces exactly one outbox row.
 *
 * <p>Tests are pure Mockito over {@link EmailDispatcher},
 * {@link SmsDispatcher}, and {@link NotificationOutboxRepository}. No real
 * SMTP, no real Mongo.
 */
@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

    @Mock
    private EmailDispatcher emailDispatcher;

    @Mock
    private SmsDispatcher smsDispatcher;

    @Mock
    private NotificationOutboxRepository outbox;

    private static final Instant FIXED = Instant.parse("2026-05-02T13:30:00Z");
    private final Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);

    @Test
    void infoSeverityRoutesToEmailOnly() {
        NotificationDispatcher dispatcher = new NotificationDispatcher(emailDispatcher, smsDispatcher, outbox, clock);
        when(emailDispatcher.dispatch(any())).thenReturn(EmailDispatcher.Outcome.sent());
        when(outbox.insertIfAbsent(any())).thenReturn(true);

        dispatcher.dispatch(sample(NotificationEvent.Severity.INFO));

        verify(emailDispatcher).dispatch(any());
        verify(smsDispatcher, never()).dispatch(any());
        // Exactly one outbox row — for the email channel.
        ArgumentCaptor<NotificationOutboxRecord> captor = ArgumentCaptor.forClass(NotificationOutboxRecord.class);
        verify(outbox, times(1)).insertIfAbsent(captor.capture());
        assertThat(captor.getValue().channel()).isEqualTo("EMAIL");
        assertThat(captor.getValue().status()).isEqualTo("SENT");
    }

    @Test
    void warnSeverityRoutesToEmailOnly() {
        NotificationDispatcher dispatcher = new NotificationDispatcher(emailDispatcher, smsDispatcher, outbox, clock);
        when(emailDispatcher.dispatch(any())).thenReturn(EmailDispatcher.Outcome.sent());
        when(outbox.insertIfAbsent(any())).thenReturn(true);

        dispatcher.dispatch(sample(NotificationEvent.Severity.WARN));

        verify(smsDispatcher, never()).dispatch(any());
        verify(outbox, times(1)).insertIfAbsent(any());
    }

    @Test
    void errorSeverityRoutesToEmailOnly() {
        NotificationDispatcher dispatcher = new NotificationDispatcher(emailDispatcher, smsDispatcher, outbox, clock);
        when(emailDispatcher.dispatch(any())).thenReturn(EmailDispatcher.Outcome.sent());
        when(outbox.insertIfAbsent(any())).thenReturn(true);

        dispatcher.dispatch(sample(NotificationEvent.Severity.ERROR));

        verify(smsDispatcher, never()).dispatch(any());
        verify(outbox, times(1)).insertIfAbsent(any());
    }

    @Test
    void criticalSeverityFansOutToEmailAndSms() {
        NotificationDispatcher dispatcher = new NotificationDispatcher(emailDispatcher, smsDispatcher, outbox, clock);
        when(emailDispatcher.dispatch(any())).thenReturn(EmailDispatcher.Outcome.sent());
        when(smsDispatcher.dispatch(any())).thenReturn(SmsDispatcher.Outcome.skipped("phase7_twilio_not_wired"));
        when(outbox.insertIfAbsent(any())).thenReturn(true);

        dispatcher.dispatch(sample(NotificationEvent.Severity.CRITICAL));

        verify(emailDispatcher).dispatch(any());
        verify(smsDispatcher).dispatch(any());
        // Two outbox rows — one per channel attempt.
        ArgumentCaptor<NotificationOutboxRecord> captor = ArgumentCaptor.forClass(NotificationOutboxRecord.class);
        verify(outbox, times(2)).insertIfAbsent(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(NotificationOutboxRecord::channel)
                .containsExactly("EMAIL", "SMS");
        // SMS row records SKIPPED with the Phase 7 marker — Phase 7 swap
        // changes the dispatcher to SENT without changing the orchestrator.
        assertThat(captor.getAllValues().get(1).status()).isEqualTo("SKIPPED");
        assertThat(captor.getAllValues().get(1).errorMessage()).contains("phase7_twilio_not_wired");
    }

    @Test
    void emailFailureRecordsFailedOutboxRow() {
        NotificationDispatcher dispatcher = new NotificationDispatcher(emailDispatcher, smsDispatcher, outbox, clock);
        when(emailDispatcher.dispatch(any())).thenReturn(EmailDispatcher.Outcome.failed("smtp 550"));
        when(outbox.insertIfAbsent(any())).thenReturn(true);

        dispatcher.dispatch(sample(NotificationEvent.Severity.ERROR));

        ArgumentCaptor<NotificationOutboxRecord> captor = ArgumentCaptor.forClass(NotificationOutboxRecord.class);
        verify(outbox).insertIfAbsent(captor.capture());
        NotificationOutboxRecord row = captor.getValue();
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.errorMessage()).contains("smtp 550");
        // The orchestrator does NOT throw on channel failure — partial fan-out
        // is recorded, the consumer commits the offset, no Kafka redelivery.
    }

    @Test
    void emailSkippedRecordsSkippedOutboxRow() {
        NotificationDispatcher dispatcher = new NotificationDispatcher(emailDispatcher, smsDispatcher, outbox, clock);
        when(emailDispatcher.dispatch(any())).thenReturn(EmailDispatcher.Outcome.skipped("smtp_host_empty"));
        when(outbox.insertIfAbsent(any())).thenReturn(true);

        dispatcher.dispatch(sample(NotificationEvent.Severity.INFO));

        ArgumentCaptor<NotificationOutboxRecord> captor = ArgumentCaptor.forClass(NotificationOutboxRecord.class);
        verify(outbox).insertIfAbsent(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo("SKIPPED");
        assertThat(captor.getValue().errorMessage()).contains("smtp_host_empty");
    }

    @Test
    void duplicateRedeliveryShortCircuitsViaOutboxDedupe() {
        // The Mongo unique index on (dedupe_key, channel) returns false from
        // insertIfAbsent on a duplicate. The dispatcher's contract: log + skip,
        // never throw, never re-attempt the channel.
        NotificationDispatcher dispatcher = new NotificationDispatcher(emailDispatcher, smsDispatcher, outbox, clock);
        when(emailDispatcher.dispatch(any())).thenReturn(EmailDispatcher.Outcome.sent());
        when(outbox.insertIfAbsent(any())).thenReturn(false); // duplicate

        // Should not throw — dedupe is the expected path on Kafka redelivery.
        dispatcher.dispatch(sample(NotificationEvent.Severity.INFO));

        // We DO call the channel before consulting the outbox in this design;
        // the (channel, outbox) ordering is documented in the dispatcher
        // header. Phase 7 may invert this for stronger AP semantics, but the
        // current contract is: send-then-record. Verify we tried email once.
        verify(emailDispatcher, times(1)).dispatch(any());
    }

    @Test
    void outboxRowFieldsCarryEventMetadata() {
        NotificationDispatcher dispatcher = new NotificationDispatcher(emailDispatcher, smsDispatcher, outbox, clock);
        when(emailDispatcher.dispatch(any())).thenReturn(EmailDispatcher.Outcome.sent());
        when(outbox.insertIfAbsent(any())).thenReturn(true);
        NotificationEvent event =
                new NotificationEvent("TENANT-99", "evt-77", "WARN", "soft halt", "body", null, FIXED, "corr-saga-1");

        dispatcher.dispatch(event);

        ArgumentCaptor<NotificationOutboxRecord> captor = ArgumentCaptor.forClass(NotificationOutboxRecord.class);
        verify(outbox).insertIfAbsent(captor.capture());
        NotificationOutboxRecord row = captor.getValue();
        assertThat(row.tenantId()).isEqualTo("TENANT-99");
        assertThat(row.eventId()).isEqualTo("evt-77");
        assertThat(row.severity()).isEqualTo("WARN");
        assertThat(row.title()).isEqualTo("soft halt");
        assertThat(row.correlationId()).isEqualTo("corr-saga-1");
        // attempted_at sourced from injected clock — replay-deterministic.
        assertThat(row.attemptedAt()).isEqualTo(FIXED);
        // dedupe_key is the deterministic sha256 of (tenantId + ":" + eventId).
        assertThat(row.dedupeKey()).hasSize(64);
        assertThat(row.dedupeKey()).matches("[0-9a-f]{64}");
    }

    private static NotificationEvent sample(NotificationEvent.Severity severity) {
        return NotificationEvent.simple("OWNER", "e-1", severity, "trade filled", "body content", FIXED);
    }
}
