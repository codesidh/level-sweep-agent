package com.levelsweep.marketdata.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only controllable {@link Clock}. Honors the project's
 * {@code replay-parity} skill: business code never calls
 * {@link Instant#now()} so tests can drive time deterministically.
 */
public final class TestClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public TestClock(Instant initial) {
        this(initial, ZoneOffset.UTC);
    }

    public TestClock(Instant initial, ZoneId zone) {
        this.now = new AtomicReference<>(initial);
        this.zone = zone;
    }

    public void tick(Duration d) {
        now.updateAndGet(i -> i.plus(d));
    }

    public void setTo(Instant target) {
        now.set(target);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new TestClock(now.get(), zone);
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
