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
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Thin proxy controller for calendar-service.
 *
 * <pre>
 * GET /api/calendar/today                              →  GET {calendarBase}/calendar/today
 * GET /api/calendar/blackout-dates?from=YYYY-MM-DD&to=…→  GET {calendarBase}/calendar/blackout-dates?from=&to=
 * GET /api/calendar/{date}                             →  GET {calendarBase}/calendar/{date}
 * </pre>
 *
 * <p>The calendar is tenant-agnostic for Phase A — NYSE schedule is the
 * same for every tenant — but we still propagate {@code X-Tenant-Id} for
 * audit consistency (calendar-service logs the tenant on each call so a
 * spike in calendar lookups can be attributed to a tenant).
 *
 * <p>Route ordering note: {@code /blackout-dates} is declared <b>before</b>
 * the catch-all {@code /{date}} so Spring's path-pattern matcher does not
 * try to parse "blackout-dates" as a date and 400.
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

    @GetMapping("/blackout-dates")
    public ResponseEntity<?> blackoutDates(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        String tenantId = resolveTenant();
        // Forward the date-range query params verbatim. calendar-service's
        // CalendarController validates the format and 400s on bad input;
        // the BFF's job is to be a thin proxy and let that error surface.
        UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/calendar/blackout-dates");
        if (from != null && !from.isBlank()) {
            uri.queryParam("from", from);
        }
        if (to != null && !to.isBlank()) {
            uri.queryParam("to", to);
        }
        String path = uri.build().toUriString();
        LOG.debug("proxying GET /api/calendar/blackout-dates from={} to={} (tenant={})", from, to, tenantId);
        try {
            String body = client.get()
                    .uri(path)
                    .header(BypassAuthFilter.TENANT_HEADER, tenantId)
                    .retrieve()
                    .body(String.class);
            return ResponseEntity.ok().body(body);
        } catch (RestClientResponseException e) {
            LOG.warn("calendar-service /blackout-dates returned {} for tenant={}", e.getStatusCode(), tenantId);
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        }
    }

    @GetMapping("/{date}")
    public ResponseEntity<?> byDate(@PathVariable("date") String date) {
        String tenantId = resolveTenant();
        LOG.debug("proxying GET /api/calendar/{} (tenant={})", date, tenantId);
        try {
            String body = client.get()
                    .uri("/calendar/" + date)
                    .header(BypassAuthFilter.TENANT_HEADER, tenantId)
                    .retrieve()
                    .body(String.class);
            return ResponseEntity.ok().body(body);
        } catch (RestClientResponseException e) {
            LOG.warn("calendar-service /{} returned {} for tenant={}", date, e.getStatusCode(), tenantId);
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
