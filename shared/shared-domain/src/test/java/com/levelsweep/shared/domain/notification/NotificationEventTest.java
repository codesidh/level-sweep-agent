package com.levelsweep.shared.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NotificationEvent}. Pure record validation —
 * compile-time, no Spring, no Kafka.
 */
class NotificationEventTest {

    private static final Instant FIXED = Instant.parse("2026-05-02T13:30:00Z");

    @Test
    void rejectsBlankTenantId() {
        assertThatThrownBy(() -> new NotificationEvent("", "e1", "INFO", "t", "b", null, FIXED, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
        assertThatThrownBy(() -> new NotificationEvent(null, "e1", "INFO", "t", "b", null, FIXED, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsBlankEventId() {
        assertThatThrownBy(() -> new NotificationEvent("OWNER", " ", "INFO", "t", "b", null, FIXED, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void rejectsBlankTitle() {
        assertThatThrownBy(() -> new NotificationEvent("OWNER", "e1", "INFO", "", "b", null, FIXED, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
    }

    @Test
    void rejectsUnknownSeverity() {
        assertThatThrownBy(() -> new NotificationEvent("OWNER", "e1", "BANANA", "t", "b", null, FIXED, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("severity");
    }

    @Test
    void normalizesSeverityToUpperCase() {
        // Case-insensitive on the wire; normalized so downstream .equals() comparisons work.
        NotificationEvent e = new NotificationEvent("OWNER", "e1", "warn", "t", "b", null, FIXED, null);
        assertThat(e.severity()).isEqualTo("WARN");
        assertThat(e.severityEnum()).isEqualTo(NotificationEvent.Severity.WARN);
    }

    @Test
    void criticalFansOutToAllChannels() {
        NotificationEvent e = NotificationEvent.simple(
                "OWNER", "e1", NotificationEvent.Severity.CRITICAL, "halt", "kill switch tripped", FIXED);
        assertThat(e.severityEnum().fanOutAll()).isTrue();
    }

    @Test
    void infoStaysOnDefaultChannelOnly() {
        NotificationEvent e =
                NotificationEvent.simple("OWNER", "e1", NotificationEvent.Severity.INFO, "fill", "trade filled", FIXED);
        assertThat(e.severityEnum().fanOutAll()).isFalse();
    }

    @Test
    void tagsDefaultToEmptyMap() {
        NotificationEvent e = NotificationEvent.simple("OWNER", "e1", NotificationEvent.Severity.INFO, "t", "b", FIXED);
        assertThat(e.tags()).isEmpty();
    }

    @Test
    void tagsAreDefensivelyCopiedAndUnmodifiable() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("trade_id", "T1");
        NotificationEvent e = new NotificationEvent("OWNER", "e1", "INFO", "t", "b", mutable, FIXED, null);
        // External mutation must NOT leak into the persisted record.
        mutable.put("evil", "value");
        assertThat(e.tags()).containsOnly(Map.entry("trade_id", "T1"));
        // Direct mutation on the record's view also rejected.
        assertThatThrownBy(() -> e.tags().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void correlationIdIsOptional() {
        NotificationEvent e = NotificationEvent.simple("OWNER", "e1", NotificationEvent.Severity.INFO, "t", "b", FIXED);
        assertThat(e.correlationId()).isNull();

        NotificationEvent withCorr = new NotificationEvent("OWNER", "e1", "INFO", "t", "b", null, FIXED, "corr-1");
        assertThat(withCorr.correlationId()).isEqualTo("corr-1");
    }
}
