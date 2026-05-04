package com.levelsweep.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API Gateway / BFF entry point — Phase 6.
 *
 * <p>Edge Spring Boot 3.x Backend-for-Frontend per CLAUDE.md tech stack and
 * architecture-spec §9 (Tier 2 edge). The BFF is the single ingress for the
 * Angular dashboard; it:
 *
 * <ol>
 *   <li>Validates the {@code X-Tenant-Id} header (Phase A) or the Auth0 JWT
 *       (Phase B, behind {@code levelsweep.feature-flags.phase-b-jwt-auth},
 *       default OFF) — see {@code auth/BypassAuthFilter} and
 *       {@code auth/Auth0JwtFilter}.</li>
 *   <li>Applies a per-tenant rate limit (100 req/min/tenant in-process via
 *       bucket4j; Phase 7 swaps in APIM external) — see
 *       {@code ratelimit/RateLimitFilter}.</li>
 *   <li>Routes downstream via Spring's {@code RestClient}:
 *       <ul>
 *         <li>{@code /api/journal/**} → journal-service</li>
 *         <li>{@code /api/config/**} → user-config-service</li>
 *         <li>{@code /api/projection/**} → projection-service</li>
 *         <li>{@code /api/calendar/**} → calendar-service</li>
 *       </ul>
 *   </li>
 *   <li>Aggregates {@code /api/dashboard/{tenantId}/summary} by fan-out to
 *       all four downstreams in parallel via CompletableFuture; partial
 *       results return a {@code degraded: true} marker.</li>
 * </ol>
 *
 * <p>Phase A authentication posture: <b>owner-only via {@code X-Tenant-Id:
 * OWNER} header</b>, no JWT. The BFF is the public-facing service in Phase A
 * deployment but only the owner has the cluster's egress IP allowlisted on
 * Auth0 / Alpaca etc. Phase B per-user OAuth + JWT validation flips on with
 * the feature flag once legal counsel completes RIA / broker-dealer review
 * (CLAUDE.md guardrail #1).
 *
 * <p>Multi-tenant: every request has its tenantId stamped on the SLF4J MDC
 * (via {@code BypassAuthFilter}) so structured logs carry it, and every
 * downstream proxy call propagates {@code X-Tenant-Id} so journal /
 * user-config / projection / calendar see the same tenant scope.
 *
 * <p>Read-only for now: only GET endpoints + {@code POST /api/projection/{
 * tenantId}/run}. Phase 5 Assistant write paths (trade approval) arrive
 * later under separate Auth0 scopes.
 */
@SpringBootApplication
public class Application {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        LOG.info("api-gateway-bff starting (Phase 6: BFF for journal/config/projection/calendar)");
        SpringApplication.run(Application.class, args);
    }
}
