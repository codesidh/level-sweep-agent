package com.levelsweep.gateway.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Phase B Auth0 JWT validation — <b>placeholder</b>.
 *
 * <p>Activated only when {@code levelsweep.feature-flags.phase-b-jwt-auth=true},
 * which flips on AFTER legal counsel completes the RIA / broker-dealer review
 * (CLAUDE.md guardrail #1). Phase A keeps this filter out of the request
 * pipeline (the {@code @ConditionalOnProperty} below skips bean registration)
 * and {@link BypassAuthFilter} handles authn via the {@code X-Tenant-Id}
 * header.
 *
 * <p>When enabled, this filter would:
 *
 * <ol>
 *   <li>Pull the {@code Authorization: Bearer <jwt>} header.</li>
 *   <li>Validate signature against Auth0's JWKS endpoint (cached) using the
 *       {@code spring-boot-starter-oauth2-resource-server} starter that's
 *       already in the build set.</li>
 *   <li>Verify {@code iss} matches the configured Auth0 issuer URI and
 *       {@code aud} matches the configured audience.</li>
 *   <li>Extract the {@code https://levelsweep.io/tenant_id} custom claim
 *       and stamp it on the MDC + propagate as {@code X-Tenant-Id} to
 *       downstreams.</li>
 * </ol>
 *
 * <p>The actual JWT decoder wiring (NimbusReactiveJwtDecoder /
 * JwtAuthenticationConverter) is left to the Phase B implementation work
 * — pinning it here would mean shipping untested, dead code that drifts
 * from the eventual real wiring. The starter is already in the dep set so
 * the implementation is a config + a few classes, not a build change.
 *
 * <p>Per CLAUDE.md guardrail #1: Phase B code stays behind feature flags
 * (default OFF). Per phase-a-b-feature-flags skill: NEVER ship Phase B
 * code that runs by default.
 */
@Component
@ConditionalOnProperty(name = "levelsweep.feature-flags.phase-b-jwt-auth", havingValue = "true", matchIfMissing = false)
public class Auth0JwtFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(Auth0JwtFilter.class);

    private final String issuerUri;
    private final String audience;

    public Auth0JwtFilter(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.audiences:}") String audience) {
        this.issuerUri = issuerUri;
        this.audience = audience;
        LOG.warn(
                "Auth0JwtFilter active (Phase B). issuer={} audience={} — placeholder, full validation pending Phase B implementation.",
                issuerUri,
                audience);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/") || path.equals("/api/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Phase B placeholder: refuses traffic until the real validator is
        // wired (fail-closed posture per CLAUDE.md guardrail #3). When the
        // flag is OFF (Phase A), this bean isn't registered at all so this
        // method never runs.
        LOG.error(
                "Phase B Auth0 JWT validation is not yet implemented — request to {} blocked.",
                request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.getWriter()
                .write("{\"error\":\"Phase B JWT validation not implemented; toggle phase-b-jwt-auth=false\"}");
    }
}
