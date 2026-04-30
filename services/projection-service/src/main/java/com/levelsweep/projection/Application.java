package com.levelsweep.projection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Projection Service entry point.
 *
 * <p>Phase 0 placeholder. Phase 6 wires AP-mode read models for the dashboard /
 * projection UI, fed by Mongo {@code projection_inputs}.
 */
@SpringBootApplication
public class Application {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        LOG.info("projection-service starting (Phase 0 hello-world)");
        SpringApplication.run(Application.class, args);
    }
}
