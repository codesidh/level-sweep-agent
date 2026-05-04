package com.levelsweep.notification.kafka;

import com.levelsweep.shared.domain.notification.NotificationEvent;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListenerConfigurer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistrar;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

/**
 * Spring Kafka wiring for the notification-service consumer pipeline.
 *
 * <p>One listener container is exposed via factory bean:
 *
 * <ul>
 *   <li>{@code notificationEventKafkaListenerContainerFactory} — typed
 *       {@link NotificationEvent} deserializer for the {@code notifications}
 *       topic (architecture-spec §12.1). Producer side is multi-service:
 *       Decision Engine, Execution Service, AI Agent Service all key by
 *       {@code tenantId}.</li>
 * </ul>
 *
 * <p>The consumer is wrapped in {@link ErrorHandlingDeserializer} so a single
 * malformed record does not kill the listener thread — Spring Kafka's default
 * error handler ({@link org.springframework.kafka.listener.DefaultErrorHandler}
 * in 3.x) retries with backoff and eventually logs + skips, rather than
 * poison-pilling the partition.
 *
 * <p>Trusted packages: {@link JsonDeserializer} requires an explicit allowlist
 * so deserialization of arbitrary payloads is not a remote-code-execution
 * vector. We pin to {@code com.levelsweep.shared.domain.*} — the only place a
 * trusted Kafka payload originates from in this codebase.
 *
 * <p>Mirrors {@code journal-service}'s {@code KafkaConfig} pattern one-for-one
 * — same base props, same trusted-packages stance, same explicit
 * {@link KafkaListenerConfigurer} marker bean for grep-ability.
 */
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:notification-service}")
    private String groupId;

    @Value("${spring.kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;

    /** Shared base props for every consumer this service runs. */
    private Map<String, Object> baseProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        // Notifications are AP-profile per architecture-spec §6 — eventually
        // delivered + deduplicated by consumer. Spring Kafka's default
        // AckMode.BATCH commits offsets only after the listener method
        // returns successfully, so a failure mid-dispatch redelivers; the
        // outbox unique index then dedupes the second attempt.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // Wrap every deserializer in an ErrorHandlingDeserializer so one
        // poison-pill payload doesn't crash the listener thread.
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        return props;
    }

    /** Typed consumer factory for the {@code notifications} topic. */
    @Bean
    public ConsumerFactory<String, NotificationEvent> notificationEventConsumerFactory() {
        Map<String, Object> props = baseProps();
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        // Trust only the LevelSweep shared-domain package — Kafka payloads
        // from anywhere else are a wire-protocol violation and must not
        // deserialize.
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.levelsweep.shared.domain.*");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, NotificationEvent.class.getName());
        // Producers emit a flat record without a class type header; tell
        // Spring not to look for one. Same convention used by execution-
        // service / journal-service so a future cross-language producer
        // doesn't need to set them.
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /** Listener container factory for typed {@link NotificationEvent} ingest. */
    @Bean
    public KafkaListenerContainerFactory<?> notificationEventKafkaListenerContainerFactory(
            ConsumerFactory<String, NotificationEvent> notificationEventConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, NotificationEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(notificationEventConsumerFactory);
        return factory;
    }

    /**
     * Marker bean — no-op endpoint registrar. Spring Kafka auto-configures
     * one if absent, but declaring it explicitly makes the Phase 6 wiring
     * grep-able and prevents a stray {@code @KafkaListener} on a missing
     * factory from being silently ignored when {@code @EnableKafka} is on.
     */
    @Bean
    public KafkaListenerConfigurer notificationListenerConfigurer() {
        return (KafkaListenerEndpointRegistrar registrar) -> {
            // intentionally empty — endpoints are auto-discovered via @KafkaListener
        };
    }
}
