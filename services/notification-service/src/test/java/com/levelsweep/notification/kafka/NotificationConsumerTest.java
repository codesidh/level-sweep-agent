package com.levelsweep.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.levelsweep.notification.dispatch.NotificationDispatcher;
import com.levelsweep.shared.domain.notification.NotificationEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link NotificationConsumer}. Drives the consumer with a
 * synthetic {@link NotificationEvent} built directly from shared-domain;
 * mocks {@link NotificationDispatcher}. No Spring Kafka, no embedded broker
 * — pure POJO exercise of the listener method's wiring.
 */
@ExtendWith(MockitoExtension.class)
class NotificationConsumerTest {

    @Mock
    private NotificationDispatcher dispatcher;

    @Test
    void onNotificationDelegatesToDispatcher() {
        NotificationConsumer consumer = new NotificationConsumer(dispatcher);
        NotificationEvent event = sample(NotificationEvent.Severity.WARN);

        consumer.onNotification(event);

        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(dispatcher).dispatch(captor.capture());
        // Multi-tenant: tenantId carried straight through.
        assertThat(captor.getValue().tenantId()).isEqualTo("OWNER");
        assertThat(captor.getValue().eventId()).isEqualTo("e-1");
        assertThat(captor.getValue().severity()).isEqualTo("WARN");
    }

    @Test
    void onNotificationPassesThroughCriticalSeverity() {
        // CRITICAL is the trigger for SMS fan-out at the dispatcher; the
        // consumer must NOT filter or downgrade severity.
        NotificationConsumer consumer = new NotificationConsumer(dispatcher);
        NotificationEvent event = sample(NotificationEvent.Severity.CRITICAL);

        consumer.onNotification(event);

        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(dispatcher).dispatch(captor.capture());
        assertThat(captor.getValue().severity()).isEqualTo("CRITICAL");
        assertThat(captor.getValue().severityEnum().fanOutAll()).isTrue();
    }

    @Test
    void onNotificationLetsRuntimeExceptionsPropagate() {
        // The consumer does NOT swallow exceptions — Spring Kafka redelivers
        // on throw, which is the right stance for AP delivery + dedupe outbox.
        NotificationConsumer consumer = new NotificationConsumer(dispatcher);
        NotificationEvent event = sample(NotificationEvent.Severity.ERROR);
        doThrow(new RuntimeException("mongo down")).when(dispatcher).dispatch(event);

        assertThatThrownBy(() -> consumer.onNotification(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("mongo down");
    }

    @Test
    void onNotificationRejectsNullEvent() {
        // @KafkaListener won't pass null, but defensive guards belt-and-braces
        // any test or future direct caller.
        NotificationConsumer consumer = new NotificationConsumer(dispatcher);
        assertThatThrownBy(() -> consumer.onNotification(null)).isInstanceOf(NullPointerException.class);
    }

    private static NotificationEvent sample(NotificationEvent.Severity severity) {
        return NotificationEvent.simple(
                "OWNER", "e-1", severity, "trade filled", "fill body", Instant.parse("2026-05-02T13:30:00Z"));
    }
}
