package com.levelsweep.gateway.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Phase A authentication: <b>header-only bypass</b>.
 *
 * <p>Phase A operates as the single OWNER tenant (CLAUDE.md guardrail #1).
 * The BFF accepts requests with {@code X-Tenant-Id: OWNER} and short-circuits
 * the auth chain — no JWT validation, no Auth0 callback, no session. The
 * tenantId is stamped on the SLF4J MDC under key {@code tenant_id} so
 * structured logs (logback-spring.xml) carry it on every entry.
 *
 * <p>This filter is a no-op when {@code levelsweep.feature-flags.phase-b-jwt-auth}
 * is true — Phase B flips that flag on AFTER legal counsel completes RIA /
 * broker-dealer review, at which point {@link Auth0JwtFilter} (currently a
 * placeholder) handles authn instead.
 *
 * <p>Phase A wire shape — what gets through:
 *
 * <pre>
 *   GET /api/dashboard/OWNER/summary
 *   X-Tenant-Id: OWNER          ← required; missing or blank → 401
 * </pre>
 *
 * <p>Health endpoints ({@code /actuator/**}, {@code /api/health}) are
 * exempt — K8s liveness/readiness probes don't carry the header.
 *
 * <p>This is the ONLY way into the BFF in Phase A. The cluster's
 * NetworkPolicy (Helm chart) restricts external ingress so the only callers
 * that can hit this filter are (a) the owner from a known IP via APIM
 * (Phase 7 — Phase 6 dev exposes via LoadBalancer) and (b) the Angular
 * dashboard, which the operator hosts on Azure Static Web Apps with the
 * X-Tenant-Id header injected by an SWA route rule.
 *
 * <p>Operator runbook reminder (NOT YET WRITTEN — Phase 7 follow-up):
 * document the X-Tenant-Id requirement in the BFF readme so an operator
 * doesn't try to curl the API without the header and get a confusing 401.
 */
@Component
public class BypassAuthFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(BypassAuthFilter.class);

    /** Header carrying the tenantId. Mirrors the architecture-spec §16.4 wire name. */
    public static final String TENANT_HEADER = "X-Tenant-Id";

    /** MDC key — must match logback-spring.xml's {@code includeMdcKeyName tenant_id}. */
    public static final String MDC_TENANT_KEY = "tenant_id";

    /** Phase A operates only on this tenant (CLAUDE.md guardrail #1). */
    public static final String OWNER_TENANT = "OWNER";

    private final boolean phaseBJwtAuthEnabled;

    public BypassAuthFilter(@Value("${levelsweep.feature-flags.phase-b-jwt-auth:false}") boolean phaseBJwtAuthEnabled) {
        this.phaseBJwtAuthEnabled = phaseBJwtAuthEnabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Probes and the BFF's own health endpoint don't carry the header.
        if (path.startsWith("/actuator/") || path.equals("/api/health")) {
            return true;
        }
        // CORS preflight: browsers send OPTIONS without custom headers (the
        // X-Tenant-Id header is sent on the actual GET/POST that follows).
        // Spring's CorsFilter handles the preflight response itself; this
        // filter must step aside so the browser's preflight succeeds.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // Phase B takes over: this filter steps aside, Auth0JwtFilter runs.
        return phaseBJwtAuthEnabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String tenantId = request.getHeader(TENANT_HEADER);
        if (tenantId == null || tenantId.isBlank()) {
            LOG.debug("rejecting {} — missing {} header", request.getRequestURI(), TENANT_HEADER);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"missing X-Tenant-Id header (Phase A bypass auth)\"}");
            return;
        }
        // Phase A guardrail #1: only the OWNER tenant. Any other id is a
        // mis-configured caller (Phase B clients shouldn't be on this code
        // path — they go through Auth0JwtFilter once the flag flips).
        if (!OWNER_TENANT.equals(tenantId)) {
            LOG.warn("rejecting non-OWNER tenant in Phase A bypass: {}", tenantId);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Phase A accepts only X-Tenant-Id: OWNER\"}");
            return;
        }
        // Stamp MDC for the duration of the request. The finally clears it so
        // the next request on the same servlet thread starts clean.
        try {
            MDC.put(MDC_TENANT_KEY, tenantId);
            request.setAttribute(MDC_TENANT_KEY, tenantId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TENANT_KEY);
        }
    }
}
