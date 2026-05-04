package com.levelsweep.gateway.routing;

import com.levelsweep.gateway.auth.BypassAuthFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Thin proxy controller for user-config-service.
 *
 * <pre>
 * GET /api/config/{tenantId}  →  GET {userConfigBase}/config/{tenantId}
 * </pre>
 *
 * <p>Phase A is read-only — there's no PUT here even though
 * user-config-service exposes one. Operator config edits in Phase A go
 * directly to user-config-service via {@code kubectl port-forward} +
 * {@code curl}; no Angular write UI yet.
 *
 * <p>Phase 5 Assistant adds write paths via approval, behind separate
 * Auth0 scopes. Until then, the BFF intentionally limits the surface to
 * GET — anyone reaching the BFF can only read.
 */
@RestController
@RequestMapping("/api/config")
public class ConfigRouteController {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigRouteController.class);

    private final RestClient client;

    public ConfigRouteController(@Qualifier("userConfigRestClient") RestClient client) {
        this.client = client;
    }

    @GetMapping("/{tenantId}")
    public ResponseEntity<?> config(@PathVariable("tenantId") String tenantId) {
        LOG.debug("proxying GET /api/config/{}", tenantId);
        try {
            String body = client.get()
                    .uri("/config/" + tenantId)
                    .header(BypassAuthFilter.TENANT_HEADER, tenantId)
                    .retrieve()
                    .body(String.class);
            return ResponseEntity.ok().body(body);
        } catch (RestClientResponseException e) {
            LOG.warn("user-config-service returned {} for tenant={}", e.getStatusCode(), tenantId);
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        }
    }
}
