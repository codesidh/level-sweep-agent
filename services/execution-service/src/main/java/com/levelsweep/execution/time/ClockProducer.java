package com.levelsweep.execution.time;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;

/**
 * CDI producer for {@link Clock}. All execution-service logic that needs the
 * current instant (Alpaca order submission timestamps, fill audit rows, EOD
 * flatten guard) should inject {@code Clock} (not call
 * {@link java.time.Instant#now()}) so tests can swap a fixed clock in via
 * Mockito or a hand-rolled stub.
 *
 * <p>Mirrors {@code com.levelsweep.decision.time.ClockProducer} and
 * {@code com.levelsweep.marketdata.time.ClockProducer} — each service module
 * keeps its own producer rather than introducing a cross-service shared-clock
 * module.
 */
@ApplicationScoped
public class ClockProducer {

    @Produces
    @ApplicationScoped
    public Clock clock() {
        return Clock.systemUTC();
    }
}
