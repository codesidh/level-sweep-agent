package com.levelsweep.execution;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Execution Service entry point.
 *
 * <p>Phase 3 Step 1 — wires the Kafka
 * {@link com.levelsweep.execution.ingest.TradeProposedConsumer} onto the
 * {@code tenant.commands} topic produced by the decision-engine's Trade Saga
 * (architecture-spec §12.1 row 5). Step 1 is foundation only: trades are routed
 * through a {@link com.levelsweep.execution.ingest.TradeRouter} indirection so
 * Step 2 (Alpaca order placement), Step 3 (fill listener), Step 4–5 (stop +
 * trailing manager), Step 6 (EOD flatten), and Step 7 (replay parity) can plug
 * in by producing a different {@code TradeRouter} bean.
 *
 * <p>The AI cannot place orders — only this service does. Idempotency: every
 * order placed in S2+ will be tagged with a deterministic
 * {@code client_order_id = sha256(tenant_id|trade_id|action)}.
 *
 * <p>This class only handles the JVM-level entry point + a startup banner. Bean
 * wiring lives in dedicated CDI classes ({@code TradeProposedConsumer},
 * {@code NoOpTradeRouter}, health checks).
 */
@QuarkusMain
public class Application implements QuarkusApplication {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    /** Consumer group joined for the {@code tenant.commands} topic. Mirrors application.yml. */
    private static final String CONSUMER_GROUP = "execution-service";

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
                "execution-service starting tenant={} consumerGroup={} topics=[tenant.commands]",
                tenantId,
                CONSUMER_GROUP);
        Quarkus.waitForExit();
        return 0;
    }
}
