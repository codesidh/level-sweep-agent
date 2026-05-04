package com.levelsweep.gateway.routing;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local health endpoint.
 *
 * <p>This is intentionally NOT a proxy to downstream {@code /actuator/health}
 * endpoints — each service exposes its own Spring Boot Actuator probe and
 * K8s queries them directly via the Helm chart's probe paths. Aggregating
 * downstream health here would create a false-positive on the BFF (BFF up
 * but one downstream down → dashboard returns degraded but liveness still
 * green).
 *
 * <p>K8s probes the BFF itself at {@code /actuator/health/liveness} and
 * {@code /actuator/health/readiness}; this {@code /api/health} is for
 * the Angular dashboard's "is the BFF reachable?" preflight.
 *
 * <p>Health is exempt from {@link com.levelsweep.gateway.auth.BypassAuthFilter}
 * (no X-Tenant-Id required) — see the {@code shouldNotFilter} override.
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "api-gateway-bff",
                "phase", "A"));
    }
}
