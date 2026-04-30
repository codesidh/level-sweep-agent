package com.levelsweep.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API Gateway / BFF entry point.
 *
 * <p>Phase 0 placeholder. Phase 6 wires:
 *
 * <ul>
 *   <li>Auth0 JWT validation + tenant_id claim extraction (architecture-spec §16.4)</li>
 *   <li>X-Tenant-Id header propagation to downstream services</li>
 *   <li>Per-tenant rate limiting (Redis-backed Spring Cloud Gateway filter)</li>
 *   <li>BFF aggregation endpoints for the Angular dashboard</li>
 *   <li>Routes: /api/config/** → user-config-service, /api/projection/** → projection-service, etc.</li>
 * </ul>
 */
@SpringBootApplication
public class Application {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        LOG.info("api-gateway-bff starting (Phase 0 hello-world)");
        SpringApplication.run(Application.class, args);
    }
}
