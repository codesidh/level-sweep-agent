package com.levelsweep.gateway.aggregation;

import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard summary endpoint — single fan-out request for the Angular home screen.
 *
 * <pre>
 * GET /api/dashboard/{tenantId}/summary
 * </pre>
 *
 * <p>Response shape (always 200, even on partial failure — see
 * {@link DashboardAggregator}):
 *
 * <pre>{@code
 * {
 *   "tenant_id": "OWNER",
 *   "config":     { ...user-config-service body... } | { "error": "..." },
 *   "journal":    { ...journal-service body...     } | { "error": "..." },
 *   "projection": { ...projection-service body...  } | { "error": "..." },
 *   "calendar":   { ...calendar-service body...    } | { "error": "..." },
 *   "degraded":   false | true
 * }
 * }</pre>
 *
 * <p>The dashboard renders {@code degraded:true} as a yellow banner and the
 * affected section as "unavailable". A 5xx from the BFF would force the
 * whole UI into an error state, which is operationally worse than showing
 * a partial dashboard with a clear "this section failed" indicator.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private static final Logger LOG = LoggerFactory.getLogger(DashboardController.class);

    private final DashboardAggregator aggregator;

    public DashboardController(DashboardAggregator aggregator) {
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
    }

    @GetMapping("/{tenantId}/summary")
    public ResponseEntity<?> summary(@PathVariable("tenantId") String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId must not be blank"));
        }
        long t0 = System.currentTimeMillis();
        Map<String, Object> body = aggregator.compose(tenantId);
        LOG.info(
                "dashboard summary tenant={} degraded={} ms={}",
                tenantId,
                body.get("degraded"),
                System.currentTimeMillis() - t0);
        return ResponseEntity.ok(body);
    }
}
