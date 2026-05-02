package com.levelsweep.aiagent.time;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;

/**
 * CDI producer for {@link Clock}. Anthropic call latency timestamps, audit row
 * {@code occurred_at} fields, and daily-cost rollovers (per America/New_York
 * wall clock — architecture-spec Principle #10) all depend on injecting
 * {@code Clock} rather than calling {@link java.time.Instant#now()} directly.
 *
 * <p>Mirrors {@code com.levelsweep.execution.time.ClockProducer} and
 * {@code com.levelsweep.decision.time.ClockProducer} — each service module
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
