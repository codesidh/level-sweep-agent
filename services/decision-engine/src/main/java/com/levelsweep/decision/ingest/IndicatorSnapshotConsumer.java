package com.levelsweep.decision.ingest;

import com.levelsweep.decision.signal.IndicatorSnapshotHolder;
import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka consumer for the {@code market.indicators.2m} topic (architecture-spec §12.1)
 * via the {@code indicators-2m} channel. Each received snapshot replaces the latest
 * value held by {@link IndicatorSnapshotHolder}, which is read by the Signal Engine
 * when scoring incoming bars.
 *
 * <p>Sibling to {@link BarConsumer} on the bar topics. Joins the same {@code decision-engine}
 * consumer group (configured per-channel in {@code application.yml}) so multiple Decision
 * Engine pods in Phase B share the partition load. The {@link IndicatorSnapshot} value comes
 * off the wire pre-deserialized via {@link IndicatorSnapshotDeserializer}, so the
 * {@code @Incoming} method receives a fully-validated record.
 *
 * <p>Holder semantics are last-value-wins (atomic reference inside
 * {@link IndicatorSnapshotHolder}); ordering across snapshots for the same symbol is
 * preserved by symbol-keyed partitioning on the producer side. Replay parity (CLAUDE.md
 * §5) is preserved because the consumer is a pass-through — no transformation, no
 * accumulation, no clock reads.
 *
 * <p>Logging: INFO on the very first snapshot received (so operators can confirm the
 * indicator topic is flowing), DEBUG on every subsequent snapshot. The first-snapshot
 * latch is not a correctness primitive — at most one extra INFO line if two threads
 * race on cold start, which is harmless.
 */
@ApplicationScoped
public class IndicatorSnapshotConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(IndicatorSnapshotConsumer.class);

    private final IndicatorSnapshotHolder holder;
    private final AtomicBoolean firstSnapshotLogged = new AtomicBoolean(false);

    @Inject
    public IndicatorSnapshotConsumer(IndicatorSnapshotHolder holder) {
        this.holder = Objects.requireNonNull(holder, "holder");
    }

    @Incoming("indicators-2m")
    public void consumeSnapshot(IndicatorSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (firstSnapshotLogged.compareAndSet(false, true)) {
            LOG.info(
                    "first indicator snapshot received symbol={} timestamp={} ema13={} ema48={} ema200={} atr14={}",
                    snapshot.symbol(),
                    snapshot.timestamp(),
                    snapshot.ema13(),
                    snapshot.ema48(),
                    snapshot.ema200(),
                    snapshot.atr14());
        } else if (LOG.isDebugEnabled()) {
            LOG.debug("indicator snapshot received symbol={} timestamp={}", snapshot.symbol(), snapshot.timestamp());
        }
        holder.setLatest(snapshot);
    }
}
