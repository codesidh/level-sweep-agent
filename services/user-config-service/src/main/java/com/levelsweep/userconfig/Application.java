package com.levelsweep.userconfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * User & Config Service entry point.
 *
 * <p>Phase 0 placeholder. Phase 6 wires per-tenant configuration CRUD (risk %, position
 * size %, profit target...) backed by MS SQL {@code tenant_configs}.
 */
@SpringBootApplication
public class Application {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        LOG.info("user-config-service starting (Phase 0 hello-world)");
        SpringApplication.run(Application.class, args);
    }
}
