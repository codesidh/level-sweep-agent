package com.levelsweep.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Trivial sanity test — confirms the test runner picks up Spring Boot 3.x
 * compile output and the JUnit 5 platform is wired correctly. Phase 6
 * intentionally avoids {@code @SpringBootTest} here: the BFF auto-loads the
 * RestClient beans wired against the configured downstream URLs at
 * context-refresh, which would push the smoke test into integration-test
 * territory.
 *
 * <p>Real coverage lives in the slice / unit tests:
 *
 * <ul>
 *   <li>{@link com.levelsweep.gateway.auth.BypassAuthFilterTest}</li>
 *   <li>{@link com.levelsweep.gateway.routing.JournalRouteControllerTest}</li>
 *   <li>{@link com.levelsweep.gateway.routing.ConfigRouteControllerTest}</li>
 *   <li>{@link com.levelsweep.gateway.routing.ProjectionRouteControllerTest}</li>
 *   <li>{@link com.levelsweep.gateway.routing.CalendarRouteControllerTest}</li>
 *   <li>{@link com.levelsweep.gateway.aggregation.DashboardAggregatorTest}</li>
 *   <li>{@link com.levelsweep.gateway.ratelimit.RateLimitFilterTest}</li>
 * </ul>
 */
class SmokeTest {

    @Test
    void truthIsTrue() {
        assertThat(true).isTrue();
    }
}
