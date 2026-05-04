package com.levelsweep.marketdata.messaging;

import com.levelsweep.marketdata.live.LivePipeline;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CDI startup observer that wires the Kafka {@link BarEmitter} and
 * {@link IndicatorSnapshotEmitter} onto the {@link LivePipeline}'s fan-outs,
 * mirroring the pattern in {@code com.levelsweep.marketdata.persistence.PersistenceWiring}.
 *
 * <p>Registered listeners are fire-and-forget at the Kafka layer (see {@link BarEmitter}
 * / {@link IndicatorSnapshotEmitter}), so a broker outage does not block the drainer
 * thread. Per-listener exception isolation lives inside {@code LivePipeline}'s fan-outs,
 * so a Kafka publish blowup cannot kill bar delivery to the indicator engine or
 * persistence sink, nor stall snapshot delivery to other subscribers.
 *
 * <p>Phase 7 enabled this in production: Strimzi/Kafka now runs in the dev
 * cluster (`infra/k8s-dev/kafka.yaml`).
 */
@ApplicationScoped
public class MessagingWiring {

    private static final Logger LOG = LoggerFactory.getLogger(MessagingWiring.class);

    private final LivePipeline pipeline;
    private final BarEmitter barEmitter;
    private final IndicatorSnapshotEmitter snapshotEmitter;

    @Inject
    public MessagingWiring(LivePipeline pipeline, BarEmitter barEmitter, IndicatorSnapshotEmitter snapshotEmitter) {
        this.pipeline = pipeline;
        this.barEmitter = barEmitter;
        this.snapshotEmitter = snapshotEmitter;
    }

    void onStart(@Observes StartupEvent ev) {
        LOG.info("registering kafka bar emitter listener (topics market.bars.{1m,2m,15m,daily})");
        pipeline.registerBarListener(bar -> {
            try {
                barEmitter.emit(bar);
            } catch (RuntimeException e) {
                LOG.warn("kafka bar emit threw: {}", e.toString());
            }
        });
        LOG.info("registering kafka indicator snapshot emitter listener (topic market.indicators.2m)");
        pipeline.registerSnapshotListener(snap -> {
            try {
                snapshotEmitter.emit(snap);
            } catch (RuntimeException e) {
                LOG.warn("kafka indicator snapshot emit threw: {}", e.toString());
            }
        });
    }
}
