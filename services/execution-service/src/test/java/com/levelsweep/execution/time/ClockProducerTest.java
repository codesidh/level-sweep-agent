package com.levelsweep.execution.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit unit tests for {@link ClockProducer}. Verifies the producer
 * returns a non-null {@link Clock}, that successive calls return consistent
 * results, and that the produced clock's zone is UTC.
 *
 * <p>Per architecture-spec §5 Principle #10: timestamps are persisted as UTC
 * {@link java.time.Instant} and the {@code America/New_York} zone is applied
 * at scheduling/FSM time-gates by the consumer (e.g., the Session FSM and the
 * EOD-flatten scheduler) — not by the {@code Clock} itself. Mirrors
 * {@code com.levelsweep.decision.time.ClockProducer} and
 * {@code com.levelsweep.marketdata.time.ClockProducer} which both produce
 * {@link Clock#systemUTC()}.
 */
class ClockProducerTest {

    @Test
    void producesNonNullClock() {
        ClockProducer producer = new ClockProducer();
        assertThat(producer.clock()).isNotNull();
    }

    @Test
    void producedClockUsesUtcZone() {
        ClockProducer producer = new ClockProducer();
        Clock clock = producer.clock();

        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void successiveCallsReturnConsistentClock() {
        ClockProducer producer = new ClockProducer();

        Clock first = producer.clock();
        Clock second = producer.clock();

        // Both calls return a system UTC clock — equal by Clock.equals (same
        // zone, same source). Independent of wall-clock movement between calls.
        assertThat(second).isEqualTo(first);
        assertThat(second.getZone()).isEqualTo(first.getZone());
    }

    @Test
    void producedClockIsAdvancing() {
        ClockProducer producer = new ClockProducer();
        Clock clock = producer.clock();

        // Two successive instants from a system clock are non-decreasing.
        // Equality is permitted because nanosecond resolution is not
        // guaranteed on all JVMs/OSes; strict monotonicity would be flaky.
        var first = clock.instant();
        var second = clock.instant();
        assertThat(second).isAfterOrEqualTo(first);
    }
}
