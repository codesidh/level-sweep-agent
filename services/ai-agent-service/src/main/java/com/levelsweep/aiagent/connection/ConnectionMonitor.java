package com.levelsweep.aiagent.connection;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the Connection FSM for a single external dependency. Phase 5 Step 1
 * uses one of these for the Anthropic Messages API. Sentinel callers consult
 * {@link #shouldShortCircuit()} to fail-OPEN per ADR-0007 §3 (the
 * {@code cb_open} fallback reason): when UNHEALTHY, the AnthropicClient
 * returns a {@link com.levelsweep.aiagent.anthropic.AnthropicResponse.TransportFailure}
 * with reason {@code "circuit_breaker_open"} and the Sentinel decision path
 * defaults to ALLOW without an HTTP call.
 *
 * <p>Threshold model (matches
 * {@code com.levelsweep.execution.fill.ConnectionMonitor} and
 * {@code com.levelsweep.marketdata.connection.ConnectionMonitor}):
 *
 * <ul>
 *   <li>3 errors within {@code errorWindow} → {@link State#DEGRADED}
 *   <li>5 errors within {@code errorWindow} → {@link State#UNHEALTHY}
 *   <li>{@code probeInterval} after going UNHEALTHY → admit one probe →
 *       {@link State#RECOVERING}
 *   <li>Successful probe → {@link State#HEALTHY}
 * </ul>
 *
 * <p>Replay-deterministic: takes a {@link Clock} so tests can use
 * {@link Clock#fixed(Instant, java.time.ZoneId)} or controllable test clocks.
 *
 * <p>NOTE: Intentionally a copy of execution-service's {@code ConnectionMonitor}.
 * The {@link State} nested enum (rather than a separate top-level class) keeps
 * the API self-contained. Phase 7 will extract the three copies (market-data,
 * execution, ai-agent) into a {@code shared-fsm} module.
 */
public final class ConnectionMonitor {

    /** Lifecycle states for an external dependency. */
    public enum State {
        HEALTHY,
        DEGRADED,
        UNHEALTHY,
        RECOVERING
    }

    private static final int DEGRADED_THRESHOLD = 3;
    private static final int UNHEALTHY_THRESHOLD = 5;
    private static final Duration DEFAULT_ERROR_WINDOW = Duration.ofSeconds(30);
    private static final Duration DEFAULT_PROBE_INTERVAL = Duration.ofSeconds(15);

    private final String dependency;
    private final Clock clock;
    private final Duration errorWindow;
    private final Duration probeInterval;
    private final Deque<Instant> recentErrors = new ArrayDeque<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.HEALTHY);
    private volatile Instant unhealthySince;

    public ConnectionMonitor(String dependency, Clock clock) {
        this(dependency, clock, DEFAULT_ERROR_WINDOW, DEFAULT_PROBE_INTERVAL);
    }

    public ConnectionMonitor(String dependency, Clock clock, Duration errorWindow, Duration probeInterval) {
        this.dependency = Objects.requireNonNull(dependency, "dependency");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.errorWindow = Objects.requireNonNull(errorWindow, "errorWindow");
        this.probeInterval = Objects.requireNonNull(probeInterval, "probeInterval");
    }

    public synchronized void recordSuccess() {
        recentErrors.clear();
        state.set(State.HEALTHY);
        unhealthySince = null;
    }

    public synchronized void recordError(Throwable cause) {
        Instant now = clock.instant();
        recentErrors.addLast(now);
        pruneOldErrors(now);
        int n = recentErrors.size();
        if (n >= UNHEALTHY_THRESHOLD) {
            state.set(State.UNHEALTHY);
            if (unhealthySince == null) {
                unhealthySince = now;
            }
        } else if (n >= DEGRADED_THRESHOLD) {
            state.compareAndSet(State.HEALTHY, State.DEGRADED);
        }
    }

    public boolean shouldShortCircuit() {
        if (state.get() != State.UNHEALTHY) {
            return false;
        }
        Instant since = unhealthySince;
        if (since == null) {
            return true;
        }
        return Duration.between(since, clock.instant()).compareTo(probeInterval) < 0;
    }

    public synchronized void admitProbe() {
        if (state.get() == State.UNHEALTHY) {
            state.set(State.RECOVERING);
        }
    }

    public State state() {
        return state.get();
    }

    public String dependency() {
        return dependency;
    }

    private void pruneOldErrors(Instant now) {
        Instant cutoff = now.minus(errorWindow);
        while (!recentErrors.isEmpty() && recentErrors.peekFirst().isBefore(cutoff)) {
            recentErrors.pollFirst();
        }
    }
}
