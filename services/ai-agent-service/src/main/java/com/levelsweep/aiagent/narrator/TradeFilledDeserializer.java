package com.levelsweep.aiagent.narrator;

import com.levelsweep.shared.domain.trade.TradeFilled;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;

/**
 * Kafka {@link org.apache.kafka.common.serialization.Deserializer} for the
 * {@link TradeFilled} record on the {@code tenant.fills} topic
 * (architecture-spec §12.1). Mirrors execution-service's
 * {@code TradeProposedDeserializer} shape — the canonical Quarkus recipe for
 * Jackson-based deserialization with a typed binding.
 *
 * <p>Wiring: referenced from {@code application.yml} as the
 * {@code value.deserializer} for the {@code trade-fills-in} incoming channel
 * in ai-agent-service. Quarkus instantiates one per channel via the standard
 * Kafka deserializer reflection pathway (no-arg constructor required), which
 * the {@link #TradeFilledDeserializer()} no-arg constructor satisfies.
 *
 * <p>Producer side: execution-service's
 * {@code com.levelsweep.execution.messaging.TradeFilledKafkaPublisher} fires
 * the value via Quarkus's untyped {@code ObjectMapperSerializer} — Jackson
 * knows the runtime type. Kafka discards type info on the wire, so the
 * consumer must explicitly bind the type via this subclass.
 */
public class TradeFilledDeserializer extends ObjectMapperDeserializer<TradeFilled> {

    public TradeFilledDeserializer() {
        super(TradeFilled.class);
    }
}
