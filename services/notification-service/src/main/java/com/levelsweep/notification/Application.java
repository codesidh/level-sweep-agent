package com.levelsweep.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Notification Service entry point.
 *
 * <p>Phase 0 placeholder. Phase 6 wires Telegram + email + SMS fan-out from the
 * {@code notifications} Kafka topic per architecture-spec §8 / §12.1.
 */
@SpringBootApplication
public class Application {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        LOG.info("notification-service starting (Phase 0 hello-world)");
        SpringApplication.run(Application.class, args);
    }
}
