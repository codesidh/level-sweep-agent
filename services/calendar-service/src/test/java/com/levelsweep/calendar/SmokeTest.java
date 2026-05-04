package com.levelsweep.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Trivial sanity test — confirms the test runner picks up Spring Boot 3.x
 * compile output and the JUnit 5 platform is wired. Phase 6 deliberately
 * does NOT use {@code @SpringBootTest} here: it would auto-load the YAML
 * resources at context-refresh and turn this into an integration test.
 *
 * <p>The real coverage lives in {@link com.levelsweep.calendar.service.CalendarServiceTest},
 * {@link com.levelsweep.calendar.api.CalendarControllerTest}, and
 * {@link com.levelsweep.calendar.domain.MarketDayTest}.
 */
class SmokeTest {

    @Test
    void truthIsTrue() {
        assertThat(true).isTrue();
    }
}
