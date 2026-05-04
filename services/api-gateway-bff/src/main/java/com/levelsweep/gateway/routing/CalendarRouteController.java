package com.levelsweep.gateway.routing;

import com.levelsweep.gateway.auth.BypassAuthFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Thin proxy controller for calendar-service.
 *
 * <pre>
 * GET /api/calendar/today  →  GET {calendarBase}/calendar/today
 * </pre>
 *
 * <p>The calendar is tenant-agnostic for Phase A — NYSE schedule is the
 * same for every tenant — but we still propagate {@code X-Tenant-Id} for
 * audit consistency (calendar-service logs the tenant on each call so a
 * spike in calendar lookups can be attributed to a tenant).
 *
 * <p>Calendar service has additional endpoints ({@code /calendar/{date}},
 * {@code /calendar/blackout-dates}) that are not exposed via the BFF in
 * Phase 6 — only the dashboard's "today" widget is wired. Phase 7 may add
 * the others if the Angular dashboard grows a calendar drill-down.
 */
@RestController
@RequestMapping("/api/calendar")
public class CalendarRouteController {

    private static final Logger LOG = LoggerFactory.getLogger(CalendarRouteController.class);

    private final RestClient client;

    public CalendarRouteController(@Qualifier("calendarRestClient") RestClient client) {
        this.client = client;
    }

    @GetMapping("/today")
    public ResponseEntity<?> today() {
        // Calendar-service is tenant-agnostic but we still forward the header
        // for log correlation. Pull the tenantId from the request attribute
        // BypassAuthFilter set, falling back to OWNER.
        String tenantId = resolveTenant();
        LOG.debug("proxying GET /api/calendar/today (tenant={})", tenantId);
        try {
            String body = client.get()
                    .uri("/calendar/today")
                    .header(BypassAuthFilter.TENANT_HEADER, tenantId)
                    .retrieve()
                    .body(String.class);
            return ResponseEntity.ok().body(body);
        } catch (RestClientResponseException e) {
            LOG.warn("calendar-service returned {} for tenant={}", e.getStatusCode(), tenantId);
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        }
    }

    private String resolveTenant() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            Object tenantAttr = sra.getRequest().getAttribute(BypassAuthFilter.MDC_TENANT_KEY);
            if (tenantAttr instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return BypassAuthFilter.OWNER_TENANT;
    }
}
