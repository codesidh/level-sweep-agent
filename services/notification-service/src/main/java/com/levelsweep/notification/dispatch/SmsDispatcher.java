package com.levelsweep.notification.dispatch;

import com.levelsweep.shared.domain.notification.NotificationEvent;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * SMS delivery channel — <b>Phase 7 stub</b>. The Twilio integration
 * (per the Phase 7 plan) will replace this with a real
 * {@code twilio.rest.api.v2010.account.MessageCreator} call; the wiring
 * for the orchestrator + outbox stays unchanged so the swap is local.
 *
 * <p>Phase 6 behavior: log "SMS channel not yet wired (Phase 7 Twilio)" at
 * INFO and return {@link Outcome#skipped(String)}. The orchestrator records
 * a SKIPPED outbox row so the audit trail captures the fan-out intent
 * (CRITICAL events targeted SMS) even though no message was sent.
 */
@Service
public class SmsDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(SmsDispatcher.class);

    /**
     * Phase 7 stub. Returns {@link Outcome#skipped(String)} unconditionally.
     * Replace the body with the Twilio call when Phase 7 lands.
     */
    public Outcome dispatch(NotificationEvent event) {
        Objects.requireNonNull(event, "event");
        // Log title only — body may contain PII (same stance as EmailDispatcher).
        LOG.info(
                "SMS channel not yet wired (Phase 7 Twilio) tenant={} eventId={} severity={} title={}",
                event.tenantId(),
                event.eventId(),
                event.severity(),
                event.title());
        return Outcome.skipped("phase7_twilio_not_wired");
    }

    /** Outcome of one dispatch attempt — symmetric with {@link EmailDispatcher.Outcome}. */
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
