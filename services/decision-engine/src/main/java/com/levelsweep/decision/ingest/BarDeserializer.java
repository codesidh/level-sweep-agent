package com.levelsweep.decision.ingest;

import com.levelsweep.shared.domain.marketdata.Bar;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;

/**
 * Kafka {@link org.apache.kafka.common.serialization.Deserializer} for the
 * {@link Bar} record, deserialized via Quarkus's auto-configured Jackson
 * {@code ObjectMapper}.
 *
 * <p>Companion to {@code com.levelsweep.marketdata.messaging.BarEmitter} on the
 * producer side — that side serializes via Quarkus's untyped
 * {@code ObjectMapperSerializer} (Jackson knows the runtime type of the value),
 * but consumers need an explicit type binding because Kafka hands us {@code byte[]}
 * with no type information attached.
 *
 * <p>Wiring: referenced from {@code application.yml} as the
 * {@code value.deserializer} for each of the four {@code bars-*} incoming
 * channels. Quarkus instantiates one per channel via the standard Kafka
 * deserializer reflection pathway (no-arg constructor required), which the
 * {@link #BarDeserializer()} no-arg constructor satisfies.
 *
 * <p>The subclass-with-no-arg-ctor pattern is the canonical Quarkus recipe — it
 * keeps the type binding visible at compile time and avoids the brittleness of
 * the {@code value.deserializer.type} string-based override (which only takes
 * effect for unparameterized {@code ObjectMapperDeserializer} usages and varies
 * by Quarkus version). See:
 * https://quarkus.io/guides/kafka#kafka-serialization
 */
public class BarDeserializer extends ObjectMapperDeserializer<Bar> {

    public BarDeserializer() {
        super(Bar.class);
    }
}
