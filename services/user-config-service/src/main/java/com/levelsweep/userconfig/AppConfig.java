package com.levelsweep.userconfig;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Service-wide bean wiring.
 *
 * <p>Exposes a UTC system clock as a Spring-managed {@link Clock} so the
 * controller and the bootstrap seeder can time-stamp inserts deterministically.
 * Tests inject a fixed clock to assert exact timestamps without flakiness.
 */
@Configuration
public class AppConfig {

    /**
     * UTC system clock — every persisted timestamp is wall-clock UTC. Pinning
     * to UTC at the bean level removes any per-pod TZ ambiguity (the AKS pods
     * run with TZ unset; UTC is the cluster default but better to be explicit).
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
