package com.levelsweep.marketdata.time;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;

/**
 * CDI producer for {@link Clock}. All business logic that needs the current
 * instant should inject {@code Clock} (not call {@link java.time.Instant#now()})
 * so tests can swap a fixed clock in via Mockito or a hand-rolled stub.
 *
 * <p>Default is {@link Clock#systemUTC()}; the {@link com.levelsweep.marketdata.live.SessionLevelScheduler}
 * rebases this onto America/New_York via {@link Clock#withZone(java.time.ZoneId)} when it
 * needs the local trading-day date.
 */
@ApplicationScoped
public class ClockProducer {

    @Produces
    @ApplicationScoped
    public Clock clock() {
        return Clock.systemUTC();
    }
}
