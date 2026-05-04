package com.levelsweep.notification.dispatch;

import com.levelsweep.shared.domain.notification.NotificationEvent;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * SMTP delivery channel. Phase A: configured for the owner's mailbox per
 * project memory ({@code codesidh@gmail.com}); Phase B will switch to
 * per-user channel preferences keyed by Auth0 user id.
 *
 * <p>Behavior:
 *
 * <ul>
 *   <li><b>SMTP host configured</b> ({@code spring.mail.host} non-empty) —
 *       send via injected {@link JavaMailSender}. Subject = event title;
 *       body = event body.</li>
 *   <li><b>SMTP host empty</b> (dev clusters without a relay) — log-only.
 *       The dispatcher still records a SKIPPED outbox row upstream so the
 *       audit trail captures "we saw this event but had no relay".</li>
 *   <li><b>Send failure</b> — wrap the underlying {@link MailException} in
 *       a {@link DispatchFailure} so the orchestrator records a FAILED
 *       outbox row with the cause. Never swallow.</li>
 * </ul>
 *
 * <p>PII: this dispatcher logs the event title at INFO. The {@code body}
 * field is intentionally NOT logged at INFO — it may contain trade detail
 * (P/L, position size). DEBUG level surfaces the body for ops debugging
 * but defaults off in prod.
 */
@Service
public class EmailDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(EmailDispatcher.class);

    private final JavaMailSender mailSender;
    private final String smtpHost;
    private final String fromAddress;
    private final String toAddress;

    public EmailDispatcher(
            JavaMailSender mailSender,
            @Value("${spring.mail.host:}") String smtpHost,
            @Value("${spring.mail.from-address:noreply@levelsweep.local}") String fromAddress,
            @Value("${levelsweep.notifications.email.to:}") String toAddress) {
        this.mailSender = Objects.requireNonNull(mailSender, "mailSender");
        this.smtpHost = smtpHost == null ? "" : smtpHost;
        this.fromAddress = Objects.requireNonNull(fromAddress, "fromAddress");
        this.toAddress = toAddress == null ? "" : toAddress;
    }

    /**
     * Dispatch one event over SMTP. Returns the outcome enum; the
     * orchestrator translates it to an outbox row.
     *
     * @return {@link Outcome#SENT} on successful handoff to JavaMailSender;
     *         {@link Outcome#SKIPPED} when SMTP host or to-address is
     *         empty (logs-only mode); never throws — channel failure is
     *         carried in the returned {@link Outcome#FAILED} value.
     */
    public Outcome dispatch(NotificationEvent event) {
        Objects.requireNonNull(event, "event");
        if (smtpHost.isBlank() || toAddress.isBlank()) {
            // Dev cluster path. Log title + tenant only — body may contain PII.
            LOG.info(
                    "email skipped (no SMTP host / to-address) tenant={} eventId={} severity={} title={}",
                    event.tenantId(),
                    event.eventId(),
                    event.severity(),
                    event.title());
            return Outcome.skipped("smtp_host_or_to_address_empty");
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(toAddress);
            message.setSubject(formatSubject(event));
            message.setText(event.body());
            mailSender.send(message);
            LOG.info(
                    "email sent tenant={} eventId={} severity={} title={}",
                    event.tenantId(),
                    event.eventId(),
                    event.severity(),
                    event.title());
            return Outcome.sent();
        } catch (MailException e) {
            // Don't log the body even on failure — same PII rationale.
            LOG.warn(
                    "email send failed tenant={} eventId={} severity={} title={} cause={}",
                    event.tenantId(),
                    event.eventId(),
                    event.severity(),
                    event.title(),
                    e.toString());
            return Outcome.failed(e.getMessage());
        }
    }

    /**
     * Subject-line format. Severity prefix in brackets so a mailbox filter
     * can route CRITICAL items to a top-priority folder without parsing
     * the body. Mirrors the structured-log convention.
     */
    private static String formatSubject(NotificationEvent event) {
        return "[" + event.severity() + "] " + event.title();
    }

    /** Outcome of one dispatch attempt — translated to an outbox row by the orchestrator. */
    public record Outcome(Status status, String message) {
        public enum Status {
            SENT,
            SKIPPED,
            FAILED
        }

        public static Outcome sent() {
            return new Outcome(Status.SENT, null);
        }

        public static Outcome skipped(String reason) {
            return new Outcome(Status.SKIPPED, reason);
        }

        public static Outcome failed(String error) {
            return new Outcome(Status.FAILED, error);
        }
    }
}
