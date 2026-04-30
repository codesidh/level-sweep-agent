package com.levelsweep.calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Calendar Service entry point.
 *
 * <p>Phase 0 placeholder. Phase 6 wires economic-calendar ingestion (news blackout
 * windows) feeding Session FSM ARMING → BLACKOUT decisions.
 */
@SpringBootApplication
public class Application {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        LOG.info("calendar-service starting (Phase 0 hello-world)");
        SpringApplication.run(Application.class, args);
    }
}
