package com.levelsweep.gateway.routing;

import com.levelsweep.gateway.auth.BypassAuthFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Thin proxy controller for projection-service.
 *
 * <pre>
 * GET  /api/projection/{tenantId}/last  →  GET  {projectionBase}/projection/{tenantId}/last
 * POST /api/projection/{tenantId}/run   →  POST {projectionBase}/projection/{tenantId}/run
 * </pre>
 *
 * <p>Note the {@code POST .../run} is a write path — it triggers a Monte
 * Carlo recompute downstream — but it's idempotent for our purposes: the
 * operator clicks "Run projection" on the dashboard and a recompute kicks
 * off. The result is consumed by the next GET .../last call.
 *
 * <p>Phase 5 Assistant write paths (trade approval) are NOT here — those
 * land on a future {@code /api/assistant/...} prefix with separate Auth0
 * scope (CLAUDE.md guardrail #2 — only Execution Service places orders;
 * Sentinel veto is the only AI write into the saga).
 */
@RestController
@RequestMapping("/api/projection")
public class ProjectionRouteController {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectionRouteController.class);

    private final RestClient client;

    public ProjectionRouteController(@Qualifier("projectionRestClient") RestClient client) {
        this.client = client;
    }

    @GetMapping("/{tenantId}/last")
    public ResponseEntity<?> last(@PathVariable("tenantId") String tenantId) {
        LOG.debug("proxying GET /api/projection/{}/last", tenantId);
        try {
            String body = client.get()
                    .uri("/projection/last/" + tenantId)
                    .header(BypassAuthFilter.TENANT_HEADER, tenantId)
                    .retrieve()
                    .body(String.class);
            return ResponseEntity.ok().body(body);
        } catch (RestClientResponseException e) {
            LOG.warn("projection-service returned {} for tenant={}", e.getStatusCode(), tenantId);
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        }
    }

    @PostMapping(value = "/{tenantId}/run", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> run(@PathVariable("tenantId") String tenantId, @RequestBody String body) {
        LOG.info("proxying POST /api/projection/{}/run", tenantId);
        try {
            String resp = client.post()
                    .uri("/projection/run")
                    .header(BypassAuthFilter.TENANT_HEADER, tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return ResponseEntity.ok().body(resp);
        } catch (RestClientResponseException e) {
            LOG.warn("projection-service /run returned {} for tenant={}", e.getStatusCode(), tenantId);
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        }
    }
}
