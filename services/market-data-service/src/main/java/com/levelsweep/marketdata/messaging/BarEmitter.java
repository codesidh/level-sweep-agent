package com.levelsweep.marketdata.messaging;

import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Timeframe;
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
 * Publishes completed bars to Kafka topics per architecture-spec §12.1.
 *
 * <p>Topic mapping (key = symbol):
 *
 * <ul>
 *   <li>{@code market.bars.1m}    — {@link Timeframe#ONE_MIN}
 *   <li>{@code market.bars.2m}    — {@link Timeframe#TWO_MIN}
 *   <li>{@code market.bars.15m}   — {@link Timeframe#FIFTEEN_MIN}
 *   <li>{@code market.bars.daily} — {@link Timeframe#DAILY}
 * </ul>
 *
 * <p>This bean is intentionally <strong>disabled in the {@code prod} profile</strong>
 * during Phase 1 — the dev cluster does not run Kafka (Strimzi lands in Phase 6
 * per architecture-spec §12). Without the gate, Quarkus eagerly constructs the
 * Kafka producer at boot and crashes on DNS resolution of the placeholder
 * {@code kafka:9092}. The gate becomes a no-op in Phase 6 when Kafka is real;
 * remove the {@link UnlessBuildProfile} at that point.
 *
 * <p>The actual topic name is configured per-channel in {@code application.yml}; this
 * class only routes by timeframe to the correct channel.
 *
 * <p>Emit pattern: fire-and-forget. {@code MutinyEmitter#send} returns a {@code Uni<Void>}
 * which we subscribe to with a no-op success and a log-on-failure handler. This avoids
 * blocking the drainer thread on Kafka acks while still surfacing publish failures via
 * the log. Quarkus's outgoing channel buffers internally, so transient broker hiccups
 * do not stall bar production.
 *
 * <p>Indicators and levels are NOT published in this PR — the architecture-spec §12.1
 * topic table doesn't list them; Phase 2 Decision Engine reads levels from MS SQL
 * {@code daily_state} directly and consumes indicator snapshots in-process.
 */
@ApplicationScoped
@UnlessBuildProfile("prod")
public class BarEmitter {

    private static final Logger LOG = LoggerFactory.getLogger(BarEmitter.class);

    private final MutinyEmitter<Record<String, Bar>> oneMin;
    private final MutinyEmitter<Record<String, Bar>> twoMin;
    private final MutinyEmitter<Record<String, Bar>> fifteenMin;
    private final MutinyEmitter<Record<String, Bar>> daily;

    @Inject
    public BarEmitter(
            @Channel("bars-1m") MutinyEmitter<Record<String, Bar>> oneMin,
            @Channel("bars-2m") MutinyEmitter<Record<String, Bar>> twoMin,
            @Channel("bars-15m") MutinyEmitter<Record<String, Bar>> fifteenMin,
            @Channel("bars-daily") MutinyEmitter<Record<String, Bar>> daily) {
        this.oneMin = Objects.requireNonNull(oneMin, "oneMin");
        this.twoMin = Objects.requireNonNull(twoMin, "twoMin");
        this.fifteenMin = Objects.requireNonNull(fifteenMin, "fifteenMin");
        this.daily = Objects.requireNonNull(daily, "daily");
    }

    /**
     * Route a completed bar to the Kafka channel matching its timeframe. The Kafka
     * record key is the symbol so all bars for one symbol land on the same partition,
     * preserving per-symbol ordering for downstream consumers.
     */
    public void emit(Bar bar) {
        Objects.requireNonNull(bar, "bar");
        Record<String, Bar> rec = Record.of(bar.symbol(), bar);
        MutinyEmitter<Record<String, Bar>> target =
                switch (bar.timeframe()) {
                    case ONE_MIN -> oneMin;
                    case TWO_MIN -> twoMin;
                    case FIFTEEN_MIN -> fifteenMin;
                    case DAILY -> daily;
                };
        target.send(rec)
                .subscribe()
                .with(
                        ignored -> {},
                        failure -> LOG.warn(
                                "kafka emit failed timeframe={} key={} cause={}",
                                bar.timeframe(),
                                bar.symbol(),
                                failure.toString()));
    }
}
