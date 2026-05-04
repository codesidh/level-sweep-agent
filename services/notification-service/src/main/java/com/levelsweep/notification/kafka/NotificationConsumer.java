package com.levelsweep.notification.kafka;

import com.levelsweep.notification.dispatch.NotificationDispatcher;
import com.levelsweep.shared.domain.notification.NotificationEvent;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for the {@code notifications} topic (architecture-spec
 * §12.1). Producer side is multi-service: Decision Engine emits
 * risk/halt alerts, Execution Service emits order rejection alerts,
 * AI Agent Service emits Sentinel-veto / Daily Reviewer alerts. Each
 * producer keys the topic by {@code tenantId} so partition stickiness
 * preserves per-tenant ordering through the fan-out.
 *
 * <p>Each consumed event is handed to {@link NotificationDispatcher}, which
 * fans out to email + SMS per the severity routing matrix and writes the
 * per-channel outbox audit row.
 *
 * <p>Determinism: the consumer's behavior is a pure function of the inbound
 * record; clock reads happen in {@link NotificationDispatcher} via its
 * injected {@link java.time.Clock}. The Kafka commit semantics
 * (auto-commit=false, AckMode.BATCH) ensure that a failure in
 * {@link NotificationDispatcher#dispatch(NotificationEvent)} causes
 * redelivery, after which the outbox unique index dedupes the second
 * attempt.
 *
 * <p>Failure mode: the dispatcher does NOT throw on channel failure
 * (those become FAILED outbox rows); we DO let through any unexpected
 * runtime exception (e.g., Mongo down on the outbox insert side). Spring
 * Kafka's default error handler retries with backoff; the AP delivery
 * profile means we tolerate the lag.
 */
@Component
public class NotificationConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationConsumer.class);

    private final NotificationDispatcher dispatcher;

    public NotificationConsumer(NotificationDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    @KafkaListener(
            topics = "notifications",
            groupId = "notification-service",
            containerFactory = "notificationEventKafkaListenerContainerFactory",
            autoStartup = "${notification.kafka.enabled:true}")
    public void onNotification(NotificationEvent event) {
        Objects.requireNonNull(event, "event");
        // Audit-safe log line — never the body. Title + severity + tenant
        // are sufficient for ops; body PII is held only in-memory + downstream.
        LOG.info(
                "received notifications topic event tenant={} eventId={} severity={} title={} correlationId={}",
                event.tenantId(),
                event.eventId(),
                event.severity(),
                event.title(),
                event.correlationId());
        dispatcher.dispatch(event);
    }
}
