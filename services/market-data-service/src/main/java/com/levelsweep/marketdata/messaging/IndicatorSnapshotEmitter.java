package com.levelsweep.marketdata.messaging;

import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import io.quarkus.arc.profile.UnlessBuildProfile;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes computed {@link IndicatorSnapshot} values to Kafka topic
 * {@code market.indicators.2m} per architecture-spec §12.1.
 *
 * <p>Sibling to {@link BarEmitter}: the same fire-and-forget pattern, the same
 * {@link UnlessBuildProfile} dev-cluster gate, the same {@link Record}-keyed-by-symbol
 * partitioning so every snapshot for one symbol lands on the same partition,
 * preserving per-symbol ordering for the Decision Engine consumer.
 *
 * <p>Why a dedicated outgoing channel: the Decision Engine's {@code IndicatorSnapshotHolder}
 * was an empty placeholder until this PR — the producer side never published
 * snapshots, so {@code SignalEvaluator} skipped every bar with reason
 * {@code "no_indicators"}. This emitter closes the loop. Phase 3 S4-S5 (Stop Watcher /
 * Trail Manager) also subscribe to this topic to evaluate {@code requirements.md} §9
 * triggers keyed by {@code (symbol, timestamp)}.
 *
 * <p>Snapshot shape is passed through unchanged — no transformation here. The replay-
 * parity contract (CLAUDE.md §5) requires that what comes off the Kafka topic equals
 * what {@link com.levelsweep.marketdata.indicators.IndicatorEngine} produced; the
 * round-trip serializer pair (this emitter's {@code ObjectMapperSerializer} and the
 * consumer's typed {@code ObjectMapperDeserializer}) preserves the record exactly.
 *
 * <p>The actual topic name is configured for this channel in {@code application.yml};
 * this class only references the channel by name.
 *
 * <p>Disabled in the {@code prod} profile during Phase 1 — same rationale as
 * {@link BarEmitter}: the dev cluster does not run Kafka (Strimzi lands in Phase 6
 * per architecture-spec §12). The {@code %prod} application.yml swap routes this
 * channel to {@code smallrye-in-memory} so the producer constructs cleanly at boot.
 */
@ApplicationScoped
@UnlessBuildProfile("prod")
public class IndicatorSnapshotEmitter {

    private static final Logger LOG = LoggerFactory.getLogger(IndicatorSnapshotEmitter.class);

    private final MutinyEmitter<Record<String, IndicatorSnapshot>> indicators2m;

    @Inject
    public IndicatorSnapshotEmitter(
            @Channel("indicators-2m") MutinyEmitter<Record<String, IndicatorSnapshot>> indicators2m) {
        this.indicators2m = Objects.requireNonNull(indicators2m, "indicators2m");
    }

    /**
     * Publish an {@link IndicatorSnapshot} to {@code market.indicators.2m}, keyed by
     * symbol so all snapshots for one symbol land on the same partition. Fire-and-forget:
     * a Kafka publish failure logs a WARN but never propagates to the calling thread.
     */
    public void emit(IndicatorSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Record<String, IndicatorSnapshot> rec = Record.of(snapshot.symbol(), snapshot);
        indicators2m
                .send(rec)
                .subscribe()
                .with(
                        ignored -> {},
                        failure -> LOG.warn(
                                "kafka indicator snapshot emit failed key={} timestamp={} cause={}",
                                snapshot.symbol(),
                                snapshot.timestamp(),
                                failure.toString()));
    }
}
