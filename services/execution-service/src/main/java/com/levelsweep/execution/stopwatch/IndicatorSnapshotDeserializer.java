package com.levelsweep.execution.stopwatch;

import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;

/**
 * Kafka {@link org.apache.kafka.common.serialization.Deserializer} for the
 * {@link IndicatorSnapshot} record, deserialized via Quarkus's auto-configured
 * Jackson {@code ObjectMapper}.
 *
 * <p>Companion to the {@code IndicatorSnapshotEmitter} in market-data-service
 * (precursor PR). Same shape exists in {@code decision-engine} as
 * {@code com.levelsweep.decision.ingest.IndicatorSnapshotDeserializer}; we
 * duplicate here rather than cross-import because shared-domain holds zero
 * Quarkus deps. Wired into {@code application.yml} as the
 * {@code value.deserializer} for the {@code indicators-2m} incoming channel.
 *
 * <p>The subclass-with-no-arg-ctor pattern is the canonical Quarkus recipe.
 * See: https://quarkus.io/guides/kafka#kafka-serialization
 */
public class IndicatorSnapshotDeserializer extends ObjectMapperDeserializer<IndicatorSnapshot> {

    public IndicatorSnapshotDeserializer() {
        super(IndicatorSnapshot.class);
    }
}
