package com.levelsweep.aiagent.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.levelsweep.aiagent.connection.ConnectionMonitor.State;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * State-machine tests for {@link ConnectionMonitor}. Verifies the Connection
 * FSM transitions described in architecture-spec §10.6:
 *
 * <pre>
 *   HEALTHY → DEGRADED → UNHEALTHY → RECOVERING → HEALTHY
 * </pre>
 *
 * <p>Pure POJO test — uses a controllable {@link Clock} so the error window
 * and probe interval are deterministic.
 */
class ConnectionMonitorTest {

    /** Mutable clock for deterministic time control. */
    static final class TestClock extends Clock {
        private final AtomicReference<Instant> now;

        TestClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }
    }

    @Test
    void startsHealthy() {
        ConnectionMonitor m = new ConnectionMonitor("anthropic", Clock.systemUTC());
        assertThat(m.state()).isEqualTo(State.HEALTHY);
        assertThat(m.dependency()).isEqualTo("anthropic");
    }

    @Test
    void cleanRunStaysHealthy() {
        ConnectionMonitor m = new ConnectionMonitor("anthropic", Clock.systemUTC());
        m.recordSuccess();
        assertThat(m.state()).isEqualTo(State.HEALTHY);
    }

    @Test
    void threeConsecutiveErrorsTransitionToDegraded() {
        TestClock clock = new TestClock(Instant.parse("2026-05-04T13:30:00Z"));
        ConnectionMonitor m = new ConnectionMonitor("anthropic", clock);

        m.recordError(new RuntimeException("err-1"));
        assertThat(m.state()).isEqualTo(State.HEALTHY);

        m.recordError(new RuntimeException("err-2"));
        assertThat(m.state()).isEqualTo(State.HEALTHY);

        m.recordError(new RuntimeException("err-3"));
        assertThat(m.state()).isEqualTo(State.DEGRADED);
    }

    @Test
    void fiveErrorsWithinWindowTransitionToUnhealthy() {
        TestClock clock = new TestClock(Instant.parse("2026-05-04T13:30:00Z"));
        ConnectionMonitor m = new ConnectionMonitor("anthropic", clock);

        for (int i = 0; i < 5; i++) {
            m.recordError(new RuntimeException("err-" + i));
        }

        assertThat(m.state()).isEqualTo(State.UNHEALTHY);
    }

    @Test
    void successResetsToHealthy() {
        TestClock clock = new TestClock(Instant.parse("2026-05-04T13:30:00Z"));
        ConnectionMonitor m = new ConnectionMonitor("anthropic", clock);

        for (int i = 0; i < 3; i++) {
            m.recordError(new RuntimeException("err-" + i));
        }
        assertThat(m.state()).isEqualTo(State.DEGRADED);

        m.recordSuccess();

        assertThat(m.state()).isEqualTo(State.HEALTHY);
    }

    @Test
    void admitProbeFromUnhealthyMovesToRecoveringAndSuccessClosesLoop() {
        TestClock clock = new TestClock(Instant.parse("2026-05-04T13:30:00Z"));
        ConnectionMonitor m = new ConnectionMonitor("anthropic", clock);

        for (int i = 0; i < 5; i++) {
            m.recordError(new RuntimeException("err-" + i));
        }
        assertThat(m.state()).isEqualTo(State.UNHEALTHY);

        m.admitProbe();
        assertThat(m.state()).isEqualTo(State.RECOVERING);

        m.recordSuccess();
        assertThat(m.state()).isEqualTo(State.HEALTHY);
    }

    @Test
    void shouldShortCircuitOnlyWhenUnhealthyWithinProbeInterval() {
        TestClock clock = new TestClock(Instant.parse("2026-05-04T13:30:00Z"));
        ConnectionMonitor m = new ConnectionMonitor("anthropic", clock, Duration.ofSeconds(30), Duration.ofSeconds(15));

        assertThat(m.shouldShortCircuit()).isFalse();

        for (int i = 0; i < 5; i++) {
            m.recordError(new RuntimeException("err-" + i));
        }
        assertThat(m.state()).isEqualTo(State.UNHEALTHY);
        assertThat(m.shouldShortCircuit()).isTrue();

        clock.advance(Duration.ofSeconds(20));
        assertThat(m.shouldShortCircuit()).isFalse();
    }

    @Test
    void errorsOutsideWindowAreEvictedAndDoNotEscalate() {
        TestClock clock = new TestClock(Instant.parse("2026-05-04T13:30:00Z"));
        ConnectionMonitor m = new ConnectionMonitor("anthropic", clock, Duration.ofSeconds(30), Duration.ofSeconds(15));

        m.recordError(new RuntimeException("old-1"));
        m.recordError(new RuntimeException("old-2"));
        clock.advance(Duration.ofSeconds(60));

        m.recordError(new RuntimeException("new-1"));

        assertThat(m.state()).isEqualTo(State.HEALTHY);
    }

    @Test
    void constructorRejectsNullArgs() {
        assertThatThrownBy(() -> new ConnectionMonitor(null, Clock.systemUTC()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ConnectionMonitor("dep", null)).isInstanceOf(NullPointerException.class);
    }
}
