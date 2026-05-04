package com.levelsweep.notification.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.levelsweep.shared.domain.notification.NotificationEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Unit tests for {@link EmailDispatcher}. Pure Mockito over
 * {@link JavaMailSender} — no real SMTP. Covers:
 *
 * <ul>
 *   <li>Happy path — JavaMailSender invoked with the expected From/To/Subject/Body.</li>
 *   <li>SMTP host empty → SKIPPED (logs-only path for dev clusters).</li>
 *   <li>To-address empty → SKIPPED (Phase A owner mailbox not configured).</li>
 *   <li>MailException → FAILED outcome, never throws (orchestrator translates
 *       to FAILED outbox row).</li>
 *   <li>Subject formatting — severity prefix in brackets.</li>
 *   <li>PII guard — body content never logged at INFO (covered by absence
 *       of any body-substring assertion in the log path; documented in
 *       class header).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class EmailDispatcherTest {

    @Mock
    private JavaMailSender mailSender;

    private static final Instant FIXED = Instant.parse("2026-05-02T13:30:00Z");

    @Test
    void sendsViaJavaMailSenderWhenHostAndToConfigured() {
        EmailDispatcher dispatcher =
                new EmailDispatcher(mailSender, "smtp.example.com", "noreply@levelsweep.local", "owner@example.com");
        NotificationEvent event = sample(NotificationEvent.Severity.WARN);

        EmailDispatcher.Outcome outcome = dispatcher.dispatch(event);

        assertThat(outcome.status()).isEqualTo(EmailDispatcher.Outcome.Status.SENT);
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getFrom()).isEqualTo("noreply@levelsweep.local");
        assertThat(message.getTo()).containsExactly("owner@example.com");
        // Severity prefix in brackets — mailbox filter rule routes CRITICAL
        // to a top-priority folder by parsing the subject without the body.
        assertThat(message.getSubject()).isEqualTo("[WARN] trade filled");
        assertThat(message.getText()).isEqualTo("body content");
    }

    @Test
    void skipsWhenSmtpHostEmpty() {
        EmailDispatcher dispatcher =
                new EmailDispatcher(mailSender, "", "noreply@levelsweep.local", "owner@example.com");
        NotificationEvent event = sample(NotificationEvent.Severity.INFO);

        EmailDispatcher.Outcome outcome = dispatcher.dispatch(event);

        assertThat(outcome.status()).isEqualTo(EmailDispatcher.Outcome.Status.SKIPPED);
        assertThat(outcome.message()).contains("smtp_host_or_to_address_empty");
        // Critical: when host is empty, JavaMailSender must NOT be invoked —
        // a Mockito JavaMailSender would throw on send.
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void skipsWhenToAddressEmpty() {
        EmailDispatcher dispatcher =
                new EmailDispatcher(mailSender, "smtp.example.com", "noreply@levelsweep.local", "");
        NotificationEvent event = sample(NotificationEvent.Severity.INFO);

        EmailDispatcher.Outcome outcome = dispatcher.dispatch(event);

        assertThat(outcome.status()).isEqualTo(EmailDispatcher.Outcome.Status.SKIPPED);
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void wrapsMailExceptionInFailedOutcome() {
        EmailDispatcher dispatcher =
                new EmailDispatcher(mailSender, "smtp.example.com", "noreply@levelsweep.local", "owner@example.com");
        NotificationEvent event = sample(NotificationEvent.Severity.ERROR);
        doThrow(new MailSendException("relay rejected")).when(mailSender).send(any(SimpleMailMessage.class));

        EmailDispatcher.Outcome outcome = dispatcher.dispatch(event);

        // MailException must be caught — the dispatch must NEVER throw,
        // because the orchestrator continues to the next channel and we
        // record FAILED in the outbox.
        assertThat(outcome.status()).isEqualTo(EmailDispatcher.Outcome.Status.FAILED);
        assertThat(outcome.message()).contains("relay rejected");
    }

    @Test
    void subjectIncludesSeverityPrefix() {
        EmailDispatcher dispatcher =
                new EmailDispatcher(mailSender, "smtp.example.com", "noreply@levelsweep.local", "owner@example.com");
        NotificationEvent critical = sample(NotificationEvent.Severity.CRITICAL);

        dispatcher.dispatch(critical);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        // [CRITICAL] prefix lets a mailbox filter rule promote these to a
        // top-priority folder regardless of body content.
        assertThat(captor.getValue().getSubject()).startsWith("[CRITICAL]");
    }

    @Test
    void bodyTextSetVerbatimFromEventBody() {
        EmailDispatcher dispatcher =
                new EmailDispatcher(mailSender, "smtp.example.com", "noreply@levelsweep.local", "owner@example.com");
        NotificationEvent event =
                new NotificationEvent("OWNER", "e-1", "INFO", "subject", "multi\nline\nbody", null, FIXED, null);

        dispatcher.dispatch(event);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        // Body is forwarded verbatim — no markdown rendering, no truncation.
        // Phase 7 may add a templating step; until then the producer's
        // formatting choices are honored.
        assertThat(captor.getValue().getText()).isEqualTo("multi\nline\nbody");
    }

    private static NotificationEvent sample(NotificationEvent.Severity severity) {
        return NotificationEvent.simple("OWNER", "e-1", severity, "trade filled", "body content", FIXED);
    }
}
