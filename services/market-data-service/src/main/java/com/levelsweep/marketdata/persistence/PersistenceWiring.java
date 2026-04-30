package com.levelsweep.marketdata.persistence;

import com.levelsweep.marketdata.live.LivePipeline;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CDI startup observer that wires persistence sinks onto the {@link LivePipeline}'s
 * bar fan-out.
 *
 * <p>Phase 1 (this PR): registers {@link MongoBarRepository#save(com.levelsweep.shared.domain.marketdata.Bar)}
 * as a bar listener so completed bars persist to the {@code bars_raw} collection.
 *
 * <p>Future S5 work will additionally inject {@link LevelsRepository} here and register
 * a session-close hook that materializes {@code Levels} rows. {@link LevelsRepository}
 * is constructed eagerly today so its Flyway migration runs at boot, but no live caller
 * invokes {@code upsert} yet.
 */
@ApplicationScoped
public class PersistenceWiring {

    private static final Logger LOG = LoggerFactory.getLogger(PersistenceWiring.class);

    private final LivePipeline pipeline;
    private final MongoBarRepository mongoBarRepository;

    // Eagerly inject so the Flyway-managed datasource bean is realized at boot,
    // even though Phase 1 has no live caller. S5 wires upsert() here.
    @SuppressWarnings("unused")
    private final LevelsRepository levelsRepository;

    @Inject
    public PersistenceWiring(
            LivePipeline pipeline, MongoBarRepository mongoBarRepository, LevelsRepository levelsRepository) {
        this.pipeline = pipeline;
        this.mongoBarRepository = mongoBarRepository;
        this.levelsRepository = levelsRepository;
    }

    void onStart(@Observes StartupEvent ev) {
        LOG.info("registering persistence bar listener (mongo bars_raw sink)");
        // Per-listener exception isolation lives inside LivePipeline's fan-out, so a
        // Mongo blip can't kill bar delivery to the indicator engine. The repository
        // itself also catches + logs, but defense-in-depth is cheap here.
        pipeline.registerBarListener(bar -> {
            try {
                mongoBarRepository.save(bar);
            } catch (RuntimeException e) {
                LOG.warn("mongo bar save threw: {}", e.toString());
            }
        });
    }
}
