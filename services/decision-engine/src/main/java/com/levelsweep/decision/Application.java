package com.levelsweep.decision;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decision Engine entry point.
 *
 * <p>Phase 2 Step 1 — wires the Kafka {@link com.levelsweep.decision.ingest.BarConsumer}
 * onto the four bar topics produced by {@code services/market-data-service}
 * ({@code market.bars.1m / 2m / 15m / daily}) under consumer group
 * {@code decision-engine}. Step 1 is foundation only: bars are routed through a
 * {@link com.levelsweep.decision.ingest.BarRouter} indirection so Step 2 (Signal
 * Engine), Step 3 (Risk Manager), and Step 4 (Strike Selector / Trade Saga / FSMs)
 * can plug in by producing a different {@code BarRouter} bean.
 *
 * <p>The AI Sentinel veto channel (architecture-spec §4.3.1) is the only inbound
 * AI write; everything else is deterministic.
 *
 * <p>This class only handles the JVM-level entry point + a startup banner. Bean
 * wiring lives in dedicated CDI classes ({@code BarConsumer}, {@code NoOpBarRouter},
 * health checks).
 */
@QuarkusMain
public class Application implements QuarkusApplication {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    /** Consumer group joined for all four bar topics. Mirrors application.yml. */
    private static final String CONSUMER_GROUP = "decision-engine";

    @ConfigProperty(name = "tenant.id", defaultValue = "OWNER")
    String tenantId;

    /** CDI no-arg constructor. */
    @Inject
    public Application() {
        // Intentionally empty — fields populated via CDI field injection.
    }

    public static void main(String[] args) {
        Quarkus.run(Application.class, args);
    }

    @Override
    public int run(String... args) {
        LOG.info(
                "decision-engine starting tenant={} consumerGroup={} topics=[market.bars.1m, market.bars.2m, market.bars.15m, market.bars.daily]",
                tenantId,
                CONSUMER_GROUP);
        Quarkus.waitForExit();
        return 0;
    }
}
