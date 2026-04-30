package com.levelsweep.marketdata.connection;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.marketdata.testsupport.TestClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ConnectionMonitorTest {

    private TestClock clock;
    private ConnectionMonitor monitor;

    @BeforeEach
    void setUp() {
        clock = new TestClock(Instant.parse("2026-04-30T13:30:00Z"));
        monitor = new ConnectionMonitor("polygon", clock, Duration.ofSeconds(30), Duration.ofSeconds(15));
    }

    @Test
    void startsHealthy() {
        assertThat(monitor.state()).isEqualTo(ConnectionState.HEALTHY);
        assertThat(monitor.dependency()).isEqualTo("polygon");
        assertThat(monitor.shouldShortCircuit()).isFalse();
    }

    @Test
    void successResetsErrorWindow() {
        monitor.recordError(new RuntimeException("e1"));
        monitor.recordError(new RuntimeException("e2"));
        monitor.recordSuccess();
        // Two more errors should not be enough to degrade — we cleared the window
        monitor.recordError(new RuntimeException("e3"));
        monitor.recordError(new RuntimeException("e4"));
        assertThat(monitor.state()).isEqualTo(ConnectionState.HEALTHY);
    }

    @Nested
    class ErrorThresholds {

        @Test
        void threeErrorsInWindowGoesDegraded() {
            monitor.recordError(new RuntimeException("e1"));
            monitor.recordError(new RuntimeException("e2"));
            monitor.recordError(new RuntimeException("e3"));
            assertThat(monitor.state()).isEqualTo(ConnectionState.DEGRADED);
            assertThat(monitor.shouldShortCircuit()).isFalse();
        }

        @Test
        void fiveErrorsInWindowGoesUnhealthy() {
            for (int i = 0; i < 5; i++) {
                monitor.recordError(new RuntimeException("e" + i));
            }
            assertThat(monitor.state()).isEqualTo(ConnectionState.UNHEALTHY);
            assertThat(monitor.shouldShortCircuit()).isTrue();
        }

        @Test
        void errorsOutsideWindowAreForgotten() {
            monitor.recordError(new RuntimeException("old1"));
            monitor.recordError(new RuntimeException("old2"));
            clock.tick(Duration.ofSeconds(31));
            monitor.recordError(new RuntimeException("new1"));
            // The two old errors are outside the 30s window; only one fresh error remains.
            assertThat(monitor.state()).isEqualTo(ConnectionState.HEALTHY);
        }
    }

    @Nested
    class ProbeAdmission {

        @Test
        void shortCircuitsImmediatelyAfterUnhealthy() {
            for (int i = 0; i < 5; i++) {
                monitor.recordError(new RuntimeException("e" + i));
            }
            assertThat(monitor.shouldShortCircuit()).isTrue();
        }

        @Test
        void admitsProbeAfterIntervalExpires() {
            for (int i = 0; i < 5; i++) {
                monitor.recordError(new RuntimeException("e" + i));
            }
            clock.tick(Duration.ofSeconds(16));
            assertThat(monitor.shouldShortCircuit()).isFalse();
        }

        @Test
        void admitProbeTransitionsToRecovering() {
            for (int i = 0; i < 5; i++) {
                monitor.recordError(new RuntimeException("e" + i));
            }
            monitor.admitProbe();
            assertThat(monitor.state()).isEqualTo(ConnectionState.RECOVERING);
        }

        @Test
        void successfulProbeReturnsToHealthy() {
            for (int i = 0; i < 5; i++) {
                monitor.recordError(new RuntimeException("e" + i));
            }
            monitor.admitProbe();
            monitor.recordSuccess();
            assertThat(monitor.state()).isEqualTo(ConnectionState.HEALTHY);
        }
    }
}
