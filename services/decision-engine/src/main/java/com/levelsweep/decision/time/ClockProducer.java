package com.levelsweep.decision.time;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;

/**
 * CDI producer for {@link Clock}. All decision-engine logic that needs the
 * current instant should inject {@code Clock} (not call {@link java.time.Instant#now()})
 * so tests can swap a fixed clock in via Mockito or a hand-rolled stub.
 *
 * <p>Mirrors {@code com.levelsweep.marketdata.time.ClockProducer} — the two
 * services live in different Quarkus modules so each maintains its own
 * producer rather than introducing a cross-service shared-clock module.
 */
@ApplicationScoped
public class ClockProducer {

    @Produces
    @ApplicationScoped
    public Clock clock() {
        return Clock.systemUTC();
    }
}
