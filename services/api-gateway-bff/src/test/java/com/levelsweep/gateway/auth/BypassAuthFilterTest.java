package com.levelsweep.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link BypassAuthFilter}.
 *
 * <p>No Spring context — drives the filter directly with mock request /
 * response objects. The filter is the only authn for Phase A so its
 * behavior is the contract (CLAUDE.md guardrail #1, "owner-only").
 */
class BypassAuthFilterTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static FilterChain noopChain() {
        return (req, resp) -> {
            // Capture that the chain was called so the test can assert
            // pass-through for the OK path. We attribute-stamp instead of
            // using a flag field for simplicity.
            ((MockHttpServletRequest) req).setAttribute("__chain_invoked__", true);
        };
    }

    @Test
    void rejectsRequestWithoutTenantHeader() throws Exception {
        BypassAuthFilter filter = new BypassAuthFilter(false);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/journal/OWNER");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, noopChain());

        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains("missing X-Tenant-Id header");
        assertThat(req.getAttribute("__chain_invoked__")).isNull();
    }

    @Test
    void rejectsBlankTenantHeader() throws Exception {
        BypassAuthFilter filter = new BypassAuthFilter(false);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/journal/OWNER");
        req.addHeader(BypassAuthFilter.TENANT_HEADER, "   ");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, noopChain());

        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsNonOwnerTenant() throws Exception {
        BypassAuthFilter filter = new BypassAuthFilter(false);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/journal/SOMEONE-ELSE");
        req.addHeader(BypassAuthFilter.TENANT_HEADER, "SOMEONE-ELSE");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, noopChain());

        // Phase A only OWNER is allowed (guardrail #1).
        assertThat(resp.getStatus()).isEqualTo(403);
        assertThat(resp.getContentAsString()).contains("X-Tenant-Id: OWNER");
    }

    @Test
    void allowsOwnerAndStampsMdc() throws Exception {
        BypassAuthFilter filter = new BypassAuthFilter(false);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/journal/OWNER");
        req.addHeader(BypassAuthFilter.TENANT_HEADER, "OWNER");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        // Within the chain we must see the MDC populated; after the filter
        // returns it must be cleared.
        FilterChain capturingChain = (rq, rs) -> {
            assertThat(MDC.get(BypassAuthFilter.MDC_TENANT_KEY)).isEqualTo("OWNER");
            assertThat(rq.getAttribute(BypassAuthFilter.MDC_TENANT_KEY)).isEqualTo("OWNER");
            ((MockHttpServletRequest) rq).setAttribute("__chain_invoked__", true);
        };

        filter.doFilter(req, resp, capturingChain);

        assertThat(resp.getStatus()).isEqualTo(200); // unset → default 200
        assertThat(req.getAttribute("__chain_invoked__")).isEqualTo(true);
        assertThat(MDC.get(BypassAuthFilter.MDC_TENANT_KEY)).isNull();
    }

    @Test
    void skipsActuatorPaths() throws Exception {
        BypassAuthFilter filter = new BypassAuthFilter(false);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health/liveness");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, noopChain());

        // Actuator probes don't carry the header — the filter must not
        // 401 them. The chain is invoked.
        assertThat(req.getAttribute("__chain_invoked__")).isEqualTo(true);
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void skipsLocalHealthEndpoint() throws Exception {
        BypassAuthFilter filter = new BypassAuthFilter(false);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, noopChain());

        assertThat(req.getAttribute("__chain_invoked__")).isEqualTo(true);
    }

    @Test
    void becomesNoopWhenPhaseBJwtAuthFlagOn() throws Exception {
        BypassAuthFilter filter = new BypassAuthFilter(true); // Phase B flag flipped
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/journal/OWNER");
        // No header — but the filter must step aside (Auth0JwtFilter takes over).
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, noopChain());

        assertThat(req.getAttribute("__chain_invoked__")).isEqualTo(true);
    }
}
