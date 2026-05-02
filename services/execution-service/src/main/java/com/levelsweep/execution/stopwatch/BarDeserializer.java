package com.levelsweep.execution.stopwatch;

import com.levelsweep.shared.domain.marketdata.Bar;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;

/**
 * Kafka {@link org.apache.kafka.common.serialization.Deserializer} for the
 * {@link Bar} record, deserialized via Quarkus's auto-configured Jackson
 * {@code ObjectMapper}.
 *
 * <p>Companion to {@code com.levelsweep.marketdata.messaging.BarEmitter} on
 * the producer side. The same shape exists in {@code decision-engine} as
 * {@code com.levelsweep.decision.ingest.BarDeserializer}; we duplicate here
 * rather than introduce a cross-service dependency because shared-domain
 * deliberately holds zero Quarkus deps (decision-engine + execution-service
 * configure their own Kafka consumer wiring).
 *
 * <p>Wiring: referenced from {@code application.yml} as the
 * {@code value.deserializer} for the {@code bars-2m} incoming channel. The
 * subclass-with-no-arg-ctor pattern is the canonical Quarkus recipe — it
 * keeps the type binding visible at compile time and avoids the brittleness
 * of the {@code value.deserializer.type} string-based override (which only
 * takes effect for unparameterized {@code ObjectMapperDeserializer} usages).
 * See: https://quarkus.io/guides/kafka#kafka-serialization
 */
public class BarDeserializer extends ObjectMapperDeserializer<Bar> {

    public BarDeserializer() {
        super(Bar.class);
    }
}
