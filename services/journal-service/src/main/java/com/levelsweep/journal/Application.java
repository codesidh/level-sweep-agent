package com.levelsweep.journal;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Journal & State Service entry point.
 *
 * <p>Phase 0 placeholder. Phase 6 wires:
 *
 * <ul>
 *   <li>MS SQL writes for orders / positions / fills / FSM transitions (CP)</li>
 *   <li>Mongo audit log + Trade Narrator output + AI call records (AP)</li>
 *   <li>Read API for projection/dashboard consumers</li>
 * </ul>
 *
 * <p>See architecture-spec §13.
 */
@QuarkusMain
public class Application implements QuarkusApplication {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        Quarkus.run(Application.class, args);
    }

    @Override
    public int run(String... args) {
        LOG.info("journal-service starting (Phase 0 hello-world)");
        Quarkus.waitForExit();
        return 0;
    }
}
