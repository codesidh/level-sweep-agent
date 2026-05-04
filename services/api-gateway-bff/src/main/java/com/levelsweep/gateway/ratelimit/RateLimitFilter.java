package com.levelsweep.gateway.ratelimit;

import com.levelsweep.gateway.auth.BypassAuthFilter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-tenant rate limit — Phase A in-process bucket4j.
 *
 * <p>Architecture-spec §12.4 mandates per-tenant rate limiting at the BFF
 * edge. Phase A keeps the limiter local (single-replica BFF deployment per
 * Helm values — one pod, one ConcurrentHashMap of buckets); Phase 7 swaps
 * the bucket store for an APIM-backed external limiter at the same policy:
 *
 * <ul>
 *   <li>Capacity: 100 requests</li>
 *   <li>Refill: 100 tokens / 1 minute (greedy refill so a tenant that hasn't
 *       called in N minutes still tops out at 100, not 100 × N)</li>
 *   <li>Breach: HTTP 429, {@code Retry-After: 60} header, JSON body</li>
 * </ul>
 *
 * <p>Filter ordering (Spring's {@code @Order} on the bean): this runs AFTER
 * {@link BypassAuthFilter} so the tenantId has been validated and stamped
 * on the MDC. Without that ordering, an unauthenticated request would
 * consume a bucket entry under whatever id it sent, which is a trivial
 * cardinality DoS.
 *
 * <p>Health endpoints ({@code /actuator/**}, {@code /api/health}) are
 * exempt — K8s probes burn 1 token/sec on readiness which would crater
 * an honest tenant's quota.
 *
 * <p>Memory bound: Phase A is single-tenant (OWNER) so the map has at most
 * one entry. Phase B with N tenants holds N {@link Bucket} objects (each
 * ~ 200 bytes); 10k tenants is 2 MiB — trivial.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // After BypassAuthFilter (which stamps MDC)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Default tokens / period — overridable via application.yml for tests. */
    private final long capacity;

    private final Duration refillPeriod;

    /** One bucket per tenantId. */
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(
            @Value("${levelsweep.rate-limit.capacity:100}") long capacity,
            @Value("${levelsweep.rate-limit.refill-period-seconds:60}") long refillSeconds) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("rate-limit capacity must be > 0, got " + capacity);
        }
        if (refillSeconds <= 0) {
            throw new IllegalArgumentException("rate-limit refill-period-seconds must be > 0, got " + refillSeconds);
        }
        this.capacity = capacity;
        this.refillPeriod = Duration.ofSeconds(refillSeconds);
        LOG.info(
                "RateLimitFilter active: {} requests per {} seconds per tenant (Phase A in-process)",
                capacity,
                refillSeconds);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/") || path.equals("/api/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // BypassAuthFilter / Auth0JwtFilter already validated and stamped the
        // tenantId on the MDC + request attribute. Pull from the attribute
        // (cheaper than re-reading the header).
        Object tenantAttr = request.getAttribute(BypassAuthFilter.MDC_TENANT_KEY);
        String tenantId = tenantAttr instanceof String s && !s.isBlank() ? s : null;

        if (tenantId == null) {
            // Defensive: BypassAuthFilter should have already 401'd this.
            // Letting it through unlimited would break the per-tenant
            // contract; reject with 401 to match Phase A's fail-closed posture.
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"missing tenantId for rate limit\"}");
            return;
        }

        Bucket bucket = buckets.computeIfAbsent(tenantId, this::newBucket);
        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
            return;
        }

        // Quota exhausted — 429 + Retry-After header (per RFC 6585 §4 and
        // RFC 7231 §7.1.3 the value is in seconds).
        long retryAfterSeconds = refillPeriod.toSeconds();
        LOG.warn("rate limit breached for tenant={} retryAfter={}s", tenantId, retryAfterSeconds);
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType("application/json");
        response.getWriter()
                .write("{\"error\":\"rate limit exceeded\",\"retry_after_seconds\":" + retryAfterSeconds + "}");
    }

    private Bucket newBucket(String tenantId) {
        // Greedy refill: at the end of refillPeriod the bucket is back to
        // `capacity` regardless of how long it sat empty. Bucket4j's
        // alternative (intervally) would credit 100 tokens at the end of
        // each period — fine for fairness but greedy matches the "100 per
        // rolling minute" intent better.
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, refillPeriod)
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /** Test seam — exposes the bucket map size for assertions. */
    int bucketCount() {
        return buckets.size();
    }
}
