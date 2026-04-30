package com.levelsweep.marketdata;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Market Data Service entry point.
 *
 * <p>Phase 0 placeholder. Phase 1 wires Polygon WebSocket ingest + bar aggregation per
 * architecture-spec §9.1.
 */
@QuarkusMain
public class Application implements QuarkusApplication {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        Quarkus.run(Application.class, args);
    }

    @Override
    public int run(String... args) {
        LOG.info("market-data-service starting (Phase 0 hello-world)");
        Quarkus.waitForExit();
        return 0;
    }
}
