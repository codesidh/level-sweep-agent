package com.levelsweep.gateway.routing;

import com.levelsweep.gateway.auth.BypassAuthFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Thin proxy controller for journal-service queries.
 *
 * <pre>
 * GET /api/journal/{tenantId}?from=&amp;to=&amp;type=  →  GET {journalBase}/audit/{tenantId}?from=&amp;to=&amp;type=
 * </pre>
 *
 * <p>Architecture-spec §9 places journal-service as the warm-path tier-2
 * service. The BFF doesn't enrich, transform, or aggregate journal data
 * here — the dashboard page that needs aggregation calls
 * {@code DashboardController} instead.
 *
 * <p>Tenant scope: the path tenantId is authoritative AND propagated as
 * {@code X-Tenant-Id} so journal-service can apply its own tenant filter
 * (defense in depth — the BFF is in front, but every service still
 * enforces its own scope per multi-tenant-readiness rules).
 *
 * <p>Phase 6 cuts the journal-service path under {@code /audit/{tenantId}}
 * — that's the {@code JournalQueryController} mapping. Phase 7 may
 * normalize service-internal paths but the BFF mapping stays
 * {@code /api/journal/{tenantId}} regardless (Angular dashboard contract).
 */
@RestController
@RequestMapping("/api/journal")
public class JournalRouteController {

    private static final Logger LOG = LoggerFactory.getLogger(JournalRouteController.class);

    private final RestClient client;

    public JournalRouteController(@Qualifier("journalRestClient") RestClient client) {
        this.client = client;
    }

    @GetMapping("/{tenantId}")
    public ResponseEntity<?> journal(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "type", required = false) String type) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/audit/" + tenantId);
        if (from != null && !from.isBlank()) {
            uri.queryParam("from", from);
        }
        if (to != null && !to.isBlank()) {
            uri.queryParam("to", to);
        }
        if (type != null && !type.isBlank()) {
            uri.queryParam("type", type);
        }
        String path = uri.build().toUriString();
        LOG.debug("proxying GET /api/journal/{} → {}", tenantId, path);
        try {
            String body = client.get()
                    .uri(path)
                    .header(BypassAuthFilter.TENANT_HEADER, tenantId)
                    .retrieve()
                    .body(String.class);
            return ResponseEntity.ok().body(body);
        } catch (RestClientResponseException e) {
            LOG.warn("journal-service returned {} for tenant={}", e.getStatusCode(), tenantId);
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        }
    }
}
