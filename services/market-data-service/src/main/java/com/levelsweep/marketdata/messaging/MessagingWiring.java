package com.levelsweep.marketdata.messaging;

import com.levelsweep.marketdata.live.LivePipeline;
import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CDI startup observer that wires the Kafka {@link BarEmitter} onto the
 * {@link LivePipeline}'s bar fan-out, mirroring the pattern in
 * {@code com.levelsweep.marketdata.persistence.PersistenceWiring}.
 *
 * <p>Registered listener is fire-and-forget at the Kafka layer (see {@link BarEmitter}),
 * so a broker outage does not block the drainer thread. Per-listener exception
 * isolation lives inside {@code LivePipeline}'s fan-out, so a Kafka publish blowup
 * cannot kill bar delivery to the indicator engine or persistence sink.
 *
 * <p>Disabled in the {@code prod} profile during Phase 1 — same rationale as
 * {@link BarEmitter}: no Kafka cluster runs in dev until Phase 6.
 */
@ApplicationScoped
@UnlessBuildProfile("prod")
public class MessagingWiring {

    private static final Logger LOG = LoggerFactory.getLogger(MessagingWiring.class);

    private final LivePipeline pipeline;
    private final BarEmitter barEmitter;

    @Inject
    public MessagingWiring(LivePipeline pipeline, BarEmitter barEmitter) {
        this.pipeline = pipeline;
        this.barEmitter = barEmitter;
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
    }
}
