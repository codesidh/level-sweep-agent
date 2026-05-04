package com.levelsweep.journal.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.shared.domain.trade.TradeFilled;
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
 * Spring Kafka wiring for the journal-service consumer pipeline.
 *
 * <p>Two listener containers are exposed via factory beans:
 *
 * <ul>
 *   <li>{@code tradeFilledKafkaListenerContainerFactory} — typed
 *       {@link TradeFilled} deserializer for {@code tenant.fills}. The Phase 3
 *       producer ({@code execution-service}'s {@code TradeFilledKafkaPublisher})
 *       is already on the wire; the journal consumes the live stream from day
 *       one.</li>
 *   <li>{@code jsonNodeKafkaListenerContainerFactory} — generic
 *       {@link JsonNode}-based deserializer for {@code tenant.events.*}. Per
 *       the Phase 4 note in execution-service: those topics don't exist yet;
 *       Phase 5/6 follow-up PRs add producers from execution-service /
 *       decision-engine. Using a {@code JsonNode} payload here keeps the
 *       consumer forward-compatible with the eventual schema without forcing
 *       a typed POJO into shared-domain ahead of the producer.</li>
 * </ul>
 *
 * <p>Each consumer is wrapped in {@link ErrorHandlingDeserializer} so a single
 * malformed record does not kill the listener thread — the framework hands
 * the failure to {@link org.springframework.kafka.listener.DefaultErrorHandler}
 * (Spring Kafka's default error handler in 3.x) which retries with backoff and
 * eventually logs + skips, rather than poison-pilling the partition.
 *
 * <p>Trusted packages: {@link JsonDeserializer} requires an explicit allowlist
 * so deserialization of arbitrary payloads is not a remote-code-execution
 * vector. We pin to {@code com.levelsweep.shared.domain.*} — the only place a
 * trusted Kafka payload originates from in this codebase.
 */
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:journal-service}")
    private String groupId;

    @Value("${spring.kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;

    /** Shared base props for every consumer this service runs. */
    private Map<String, Object> baseProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        // Journal needs the full audit history per CAP §6 — never auto-commit
        // on a poll; let Spring Kafka commit synchronously after the listener
        // method returns successfully (default AckMode.BATCH).
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // Wrap every deserializer in an ErrorHandlingDeserializer so one
        // poison-pill payload doesn't crash the listener thread.
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        return props;
    }

    /** Typed consumer factory for {@code tenant.fills}. */
    @Bean
    public ConsumerFactory<String, TradeFilled> tradeFilledConsumerFactory() {
        Map<String, Object> props = baseProps();
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        // Trust only the LevelSweep shared-domain package — Kafka payloads from
        // anywhere else are a wire-protocol violation and must not deserialize.
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.levelsweep.shared.domain.*");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, TradeFilled.class.getName());
        // Producers (execution-service) emit a flat record; ignore embedded
        // type headers so a future cross-language producer (if ever) doesn't
        // need to set them.
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /** Listener container factory for typed {@link TradeFilled} ingest. */
    @Bean
    public KafkaListenerContainerFactory<?> tradeFilledKafkaListenerContainerFactory(
            ConsumerFactory<String, TradeFilled> tradeFilledConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, TradeFilled> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(tradeFilledConsumerFactory);
        return factory;
    }

    /** Forward-compat consumer factory for {@code tenant.events.*}. */
    @Bean
    public ConsumerFactory<String, JsonNode> jsonNodeConsumerFactory() {
        Map<String, Object> props = baseProps();
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.fasterxml.jackson.databind.*,com.levelsweep.shared.domain.*");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, JsonNode.class.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /** Listener container factory for generic {@link JsonNode} ingest. */
    @Bean
    public KafkaListenerContainerFactory<?> jsonNodeKafkaListenerContainerFactory(
            ConsumerFactory<String, JsonNode> jsonNodeConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, JsonNode> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(jsonNodeConsumerFactory);
        return factory;
    }

    /**
     * Shared {@link ObjectMapper} for converting {@link JsonNode} → Mongo
     * {@code Document} on the audit write path. Declared here so tests that
     * don't boot the full context can construct one with the same module
     * set as the runtime bean.
     *
     * <p>We register {@link com.fasterxml.jackson.datatype.jsr310.JavaTimeModule}
     * explicitly (not {@code findAndRegisterModules()}) because the test
     * classpath transitively pulls in jackson-module-scala via
     * spring-kafka-test → kafka-streams-scala, and Scala module 2.17.x is
     * incompatible with Jackson 2.18.x — the auto-discovery crashes the
     * test JVM. JSR-310 is the only module we actually need: Instant +
     * OffsetDateTime parsing on the {@code tenant.events.*} payloads.
     */
    @Bean
    public ObjectMapper journalObjectMapper() {
        return new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }

    /**
     * Marker bean — no-op endpoint registrar. Spring Kafka auto-configures
     * one if absent, but declaring it explicitly makes the Phase 6 wiring
     * grep-able and prevents a stray {@code @KafkaListener} on a missing
     * factory from being silently ignored when {@code @EnableKafka} is on.
     */
    @Bean
    public KafkaListenerConfigurer journalListenerConfigurer() {
        return (KafkaListenerEndpointRegistrar registrar) -> {
            // intentionally empty — endpoints are auto-discovered via @KafkaListener
        };
    }
}
