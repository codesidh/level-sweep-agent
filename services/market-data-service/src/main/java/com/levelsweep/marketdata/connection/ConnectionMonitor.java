package com.levelsweep.marketdata.connection;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the Connection FSM for a single external dependency.
 *
 * <p>Phase 1 — used by the Alpaca WebSocket client. Same shape will be
 * reused (verbatim or via a generic in shared-fsm) for Alpaca, Anthropic,
 * MS SQL, Mongo, and Kafka in later phases.
 *
 * <p>Threshold model:
 *
 * <ul>
 *   <li>3 errors within {@code errorWindow} → {@link ConnectionState#DEGRADED}
 *   <li>5 errors within {@code errorWindow} → {@link ConnectionState#UNHEALTHY}
 *       (circuit-breaker opens; {@link #shouldShortCircuit()} returns true)
 *   <li>{@code probeInterval} after going UNHEALTHY → admit one probe →
 *       {@link ConnectionState#RECOVERING}
 *   <li>Successful probe / clean operation → {@link ConnectionState#HEALTHY}
 * </ul>
 *
 * <p>Replay-deterministic: takes a {@link Clock} so tests can use
 * {@link Clock#fixed(Instant, java.time.ZoneId)} or controllable test clocks.
 */
public final class ConnectionMonitor {

    private static final int DEGRADED_THRESHOLD = 3;
    private static final int UNHEALTHY_THRESHOLD = 5;
    private static final Duration DEFAULT_ERROR_WINDOW = Duration.ofSeconds(30);
    private static final Duration DEFAULT_PROBE_INTERVAL = Duration.ofSeconds(15);

    private final String dependency;
    private final Clock clock;
    private final Duration errorWindow;
    private final Duration probeInterval;
    private final Deque<Instant> recentErrors = new ArrayDeque<>();
    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.HEALTHY);
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
        state.set(ConnectionState.HEALTHY);
        unhealthySince = null;
    }

    public synchronized void recordError(Throwable cause) {
        Instant now = clock.instant();
        recentErrors.addLast(now);
        pruneOldErrors(now);
        int n = recentErrors.size();
        if (n >= UNHEALTHY_THRESHOLD) {
            state.set(ConnectionState.UNHEALTHY);
            if (unhealthySince == null) {
                unhealthySince = now;
            }
        } else if (n >= DEGRADED_THRESHOLD) {
            state.compareAndSet(ConnectionState.HEALTHY, ConnectionState.DEGRADED);
        }
    }

    /**
     * Whether the FSM is open (UNHEALTHY) AND we should not yet attempt a probe.
     * Hot-path callers consult this to decide fail-closed vs. retry.
     */
    public boolean shouldShortCircuit() {
        if (state.get() != ConnectionState.UNHEALTHY) {
            return false;
        }
        Instant since = unhealthySince;
        if (since == null) {
            return true;
        }
        return Duration.between(since, clock.instant()).compareTo(probeInterval) < 0;
    }

    /**
     * Admit a probe attempt. Transitions UNHEALTHY → RECOVERING. The caller is
     * responsible for invoking {@link #recordSuccess()} on probe success or
     * {@link #recordError(Throwable)} on failure.
     */
    public synchronized void admitProbe() {
        if (state.get() == ConnectionState.UNHEALTHY) {
            state.set(ConnectionState.RECOVERING);
        }
    }

    public ConnectionState state() {
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
