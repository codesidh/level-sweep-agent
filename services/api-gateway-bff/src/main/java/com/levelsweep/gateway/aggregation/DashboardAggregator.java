package com.levelsweep.gateway.aggregation;

import com.levelsweep.gateway.auth.BypassAuthFilter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Composes the dashboard summary from four downstream services in parallel.
 *
 * <p>The Angular dashboard's summary screen needs:
 *
 * <ul>
 *   <li>Tenant config (user-config-service)</li>
 *   <li>Last 10 trade narratives (journal-service)</li>
 *   <li>Last projection (projection-service)</li>
 *   <li>Today's calendar status (calendar-service)</li>
 * </ul>
 *
 * <p>Naive serial proxy would be 4 × ~150ms = 600ms; parallel fan-out via
 * {@link CompletableFuture#supplyAsync} (running on the common ForkJoinPool —
 * fine for blocking IO at Phase A volumes) drops the wall-clock to
 * max(150ms) ≈ 150ms.
 *
 * <p>Resilience contract: <b>partial results are valid</b>. If one downstream
 * throws or times out (the {@code RestClientConfig} sets read=5s), the
 * aggregator records the failure under that section and adds a top-level
 * {@code degraded: true} marker. The dashboard renders what it has and tells
 * the operator which section is stale. This is opposite of the order path
 * (CLAUDE.md guardrail #3, fail-closed): the BFF is read-only and a partial
 * dashboard is more useful than a 500.
 *
 * <p>Multi-tenant: every fan-out call propagates {@code X-Tenant-Id: tenantId}
 * so each downstream applies its own tenant filter (defense in depth per the
 * multi-tenant-readiness skill).
 */
@Component
public class DashboardAggregator {

    private static final Logger LOG = LoggerFactory.getLogger(DashboardAggregator.class);

    /** Wall-clock cap on any single downstream — defends against a slow downstream stalling the whole aggregation. */
    private static final long PER_CALL_TIMEOUT_SECONDS = 6L; // > 5s read timeout in RestClientConfig

    private final RestClient journalClient;
    private final RestClient userConfigClient;
    private final RestClient projectionClient;
    private final RestClient calendarClient;

    public DashboardAggregator(
            @Qualifier("journalRestClient") RestClient journalClient,
            @Qualifier("userConfigRestClient") RestClient userConfigClient,
            @Qualifier("projectionRestClient") RestClient projectionClient,
            @Qualifier("calendarRestClient") RestClient calendarClient) {
        this.journalClient = Objects.requireNonNull(journalClient, "journalClient");
        this.userConfigClient = Objects.requireNonNull(userConfigClient, "userConfigClient");
        this.projectionClient = Objects.requireNonNull(projectionClient, "projectionClient");
        this.calendarClient = Objects.requireNonNull(calendarClient, "calendarClient");
    }

    /**
     * Compose the dashboard summary for {@code tenantId}.
     *
     * @return ordered map: keys {@code config}, {@code journal}, {@code projection},
     *     {@code calendar}, {@code degraded}; failed sections carry a string error.
     */
    public Map<String, Object> compose(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }

        CompletableFuture<Object> configF = supply("config", () -> userConfigClient
                .get()
                .uri("/config/" + tenantId)
                .header(BypassAuthFilter.TENANT_HEADER, tenantId)
                .retrieve()
                .body(String.class));

        CompletableFuture<Object> journalF = supply("journal", () -> journalClient
                .get()
                .uri("/audit/" + tenantId + "?limit=10")
                .header(BypassAuthFilter.TENANT_HEADER, tenantId)
                .retrieve()
                .body(String.class));

        CompletableFuture<Object> projectionF = supply("projection", () -> projectionClient
                .get()
                .uri("/projection/" + tenantId + "/last")
                .header(BypassAuthFilter.TENANT_HEADER, tenantId)
                .retrieve()
                .body(String.class));

        CompletableFuture<Object> calendarF = supply("calendar", () -> calendarClient
                .get()
                .uri("/calendar/today")
                .header(BypassAuthFilter.TENANT_HEADER, tenantId)
                .retrieve()
                .body(String.class));

        // LinkedHashMap pins the JSON property order so the Angular consumer
        // sees the same shape on every response (helps debugging on the wire).
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant_id", tenantId);
        boolean degraded = false;

        // config
        DegradeResult c = await(configF, "config");
        out.put("config", c.value);
        degraded |= c.failed;
        // journal
        DegradeResult j = await(journalF, "journal");
        out.put("journal", j.value);
        degraded |= j.failed;
        // projection
        DegradeResult p = await(projectionF, "projection");
        out.put("projection", p.value);
        degraded |= p.failed;
        // calendar
        DegradeResult ca = await(calendarF, "calendar");
        out.put("calendar", ca.value);
        degraded |= ca.failed;

        out.put("degraded", degraded);
        if (degraded) {
            LOG.warn("dashboard summary degraded for tenant={}", tenantId);
        }
        return out;
    }

    private CompletableFuture<Object> supply(String section, java.util.function.Supplier<Object> sup) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return sup.get();
            } catch (RuntimeException e) {
                // Re-throw so the await() path catches it and marks degraded.
                LOG.warn("dashboard section '{}' failed: {}", section, e.toString());
                throw e;
            }
        });
    }

    private DegradeResult await(CompletableFuture<Object> f, String section) {
        try {
            Object v = f.get(PER_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return new DegradeResult(v, false);
        } catch (TimeoutException te) {
            f.cancel(true);
            return new DegradeResult(Map.of("error", section + " timed out"), true);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new DegradeResult(Map.of("error", section + " interrupted"), true);
        } catch (ExecutionException ee) {
            String msg = ee.getCause() != null ? ee.getCause().getMessage() : ee.getMessage();
            return new DegradeResult(Map.of("error", section + " failed: " + msg), true);
        }
    }

    private record DegradeResult(Object value, boolean failed) {}
}
