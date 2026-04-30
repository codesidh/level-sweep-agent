package com.levelsweep.execution;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Execution Service entry point.
 *
 * <p>Phase 0 placeholder. Phase 3 wires Alpaca order placement, stop watcher, trailing
 * manager, EOD flatten (architecture-spec §9.4). The AI cannot place orders — only this
 * service does.
 *
 * <p>Idempotency: every order tagged with deterministic
 * {@code client_order_id = sha256(tenant_id|trade_id|action)}.
 */
@QuarkusMain
public class Application implements QuarkusApplication {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        Quarkus.run(Application.class, args);
    }

    @Override
    public int run(String... args) {
        LOG.info("execution-service starting (Phase 0 hello-world)");
        Quarkus.waitForExit();
        return 0;
    }
}
