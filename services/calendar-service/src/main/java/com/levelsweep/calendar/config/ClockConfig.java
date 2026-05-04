package com.levelsweep.calendar.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a {@link Clock} bean so {@link com.levelsweep.calendar.service.CalendarService}
 * can be tested deterministically (tests inject a {@code Clock.fixed(...)}
 * bean to pin "today" to a known date).
 *
 * <p>Production: {@link Clock#systemDefaultZone()}. The CalendarService converts
 * to {@code America/New_York} at the lookup site, so the host TZ does not
 * affect correctness — a pod running in UTC and a pod running in ET both
 * compute the same NYSE-local date.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}
