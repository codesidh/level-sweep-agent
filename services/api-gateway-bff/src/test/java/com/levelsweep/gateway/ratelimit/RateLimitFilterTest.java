package com.levelsweep.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.levelsweep.gateway.auth.BypassAuthFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link RateLimitFilter}.
 *
 * <p>Drives the filter directly with a {@link MockHttpServletRequest} that
 * has the tenant attribute pre-populated (simulating BypassAuthFilter having
 * run upstream).
 */
class RateLimitFilterTest {

    private static FilterChain countingChain(int[] counter) {
        return (req, resp) -> counter[0]++;
    }

    private static MockHttpServletRequest reqFor(String tenantId) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/journal/" + tenantId);
        r.setAttribute(BypassAuthFilter.MDC_TENANT_KEY, tenantId);
        return r;
    }

    @Test
    void hundredthRequestPassesAndHundredFirstReturns429() throws Exception {
        // Capacity 100, 60-second refill period.
        RateLimitFilter filter = new RateLimitFilter(100, 60);
        int[] passed = {0};
        FilterChain chain = countingChain(passed);

        for (int i = 0; i < 100; i++) {
            MockHttpServletRequest req = reqFor("OWNER");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(req, resp, chain);
            assertThat(resp.getStatus()).as("request #%d", i + 1).isEqualTo(200);
        }
        assertThat(passed[0]).isEqualTo(100);

        // The 101st request must hit the limit.
        MockHttpServletRequest reqOverflow = reqFor("OWNER");
        MockHttpServletResponse respOverflow = new MockHttpServletResponse();
        filter.doFilter(reqOverflow, respOverflow, chain);

        assertThat(respOverflow.getStatus()).isEqualTo(429);
        assertThat(respOverflow.getHeader("Retry-After")).isEqualTo("60");
        assertThat(respOverflow.getContentAsString()).contains("rate limit exceeded");
        // Chain must NOT have been invoked for the rejected request.
        assertThat(passed[0]).isEqualTo(100);
    }

    @Test
    void perTenantBucketsAreIndependent() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(2, 60);
        int[] passed = {0};
        FilterChain chain = countingChain(passed);

        // Burn OWNER's bucket.
        for (int i = 0; i < 2; i++) {
            filter.doFilter(reqFor("OWNER"), new MockHttpServletResponse(), chain);
        }
        MockHttpServletResponse owner3 = new MockHttpServletResponse();
        filter.doFilter(reqFor("OWNER"), owner3, chain);
        assertThat(owner3.getStatus()).isEqualTo(429);

        // ACME tenant has its own bucket — first call passes.
        MockHttpServletResponse acme = new MockHttpServletResponse();
        filter.doFilter(reqFor("ACME"), acme, chain);
        assertThat(acme.getStatus()).isEqualTo(200);
        assertThat(passed[0]).isEqualTo(3); // 2 OWNER + 1 ACME (the third OWNER was rejected)
    }

    @Test
    void missingTenantAttributeReturns401() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(100, 60);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/journal/OWNER");
        // No tenantId attribute — BypassAuthFilter would have caught this
        // upstream but RateLimitFilter is defensive.
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, (rq, rs) -> {});

        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void skipsActuatorAndLocalHealth() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, 60);
        int[] passed = {0};
        FilterChain chain = countingChain(passed);

        // Two probes that should both bypass the bucket.
        filter.doFilter(
                new MockHttpServletRequest("GET", "/actuator/health/liveness"), new MockHttpServletResponse(), chain);
        filter.doFilter(new MockHttpServletRequest("GET", "/api/health"), new MockHttpServletResponse(), chain);
        assertThat(passed[0]).isEqualTo(2);

        // OWNER bucket is untouched; capacity 1 → still has 1 token left.
        MockHttpServletResponse r = new MockHttpServletResponse();
        filter.doFilter(reqFor("OWNER"), r, chain);
        assertThat(r.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsInvalidConfig() {
        assertThatThrownBy(() -> new RateLimitFilter(0, 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitFilter(100, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
