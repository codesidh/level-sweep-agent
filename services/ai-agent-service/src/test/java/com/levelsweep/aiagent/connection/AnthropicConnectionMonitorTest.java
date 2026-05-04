package com.levelsweep.aiagent.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.levelsweep.aiagent.connection.ConnectionMonitor.State;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Smoke tests for the {@link AnthropicConnectionMonitor} CDI wrapper. The
 * underlying threshold + lifecycle behavior is fully exercised by
 * {@link ConnectionMonitorTest}; this class only proves the bean delegates and
 * pins the {@code dependency=anthropic} label that alert #11 reads.
 */
class AnthropicConnectionMonitorTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-05-04T13:30:00Z"), ZoneOffset.UTC);

    @Test
    void startsHealthyWithAnthropicDependency() {
        AnthropicConnectionMonitor monitor = new AnthropicConnectionMonitor(FIXED_CLOCK);

        assertThat(monitor.state()).isEqualTo(State.HEALTHY);
        assertThat(monitor.dependency()).isEqualTo(AnthropicConnectionMonitor.DEPENDENCY);
        assertThat(monitor.shouldShortCircuit()).isFalse();
    }

    @Test
    void recordSuccessKeepsHealthy() {
        AnthropicConnectionMonitor monitor = new AnthropicConnectionMonitor(FIXED_CLOCK);
        monitor.recordSuccess();
        assertThat(monitor.state()).isEqualTo(State.HEALTHY);
    }

    @Test
    void fiveErrorsTriggerShortCircuit() {
        AnthropicConnectionMonitor monitor = new AnthropicConnectionMonitor(FIXED_CLOCK);
        for (int i = 0; i < 5; i++) {
            monitor.recordError(new RuntimeException("err-" + i));
        }
        assertThat(monitor.state()).isEqualTo(State.UNHEALTHY);
        assertThat(monitor.shouldShortCircuit()).isTrue();
    }

    @Test
    void admitProbeMovesUnhealthyToRecovering() {
        AnthropicConnectionMonitor monitor = new AnthropicConnectionMonitor(FIXED_CLOCK);
        for (int i = 0; i < 5; i++) {
            monitor.recordError(new RuntimeException("err-" + i));
        }
        monitor.admitProbe();
        assertThat(monitor.state()).isEqualTo(State.RECOVERING);
    }

    @Test
    void rejectsNullClock() {
        assertThatNullPointerException().isThrownBy(() -> new AnthropicConnectionMonitor(null));
    }
}
