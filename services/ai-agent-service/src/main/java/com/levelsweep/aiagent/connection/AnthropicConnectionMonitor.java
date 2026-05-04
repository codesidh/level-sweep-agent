package com.levelsweep.aiagent.connection;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.Objects;

/**
 * CDI bean wrapping a {@link ConnectionMonitor} scoped to the Anthropic
 * Messages API dependency. Sole producer of the {@code dependency=anthropic}
 * Connection FSM signal observed by alert #11
 * ({@code anthropic_cb_unhealthy} in {@code infra/modules/observability/alerts.tf}).
 *
 * <p>Wired into {@link com.levelsweep.aiagent.anthropic.AnthropicClient} between
 * the cost-cap pre-flight and the HTTP call:
 *
 * <ul>
 *   <li>{@link #shouldShortCircuit()} pre-call → return TransportFailure with
 *       reason {@code circuit_breaker_open} (Sentinel: ADR-0007 §3 fail-OPEN
 *       {@code cb_open}; Narrator/Reviewer: skip).</li>
 *   <li>{@link #recordSuccess()} on 2xx.</li>
 *   <li>{@link #recordError(Throwable)} on TransportFailure / 5xx / 503.</li>
 *   <li>{@link #admitProbe()} once before sending after the probe interval
 *       elapses, so a probe in flight transitions UNHEALTHY → RECOVERING.</li>
 * </ul>
 */
@ApplicationScoped
public class AnthropicConnectionMonitor {

    /** Stable label used by the connection_state gauge and alert KQL. */
    public static final String DEPENDENCY = "anthropic";

    private final ConnectionMonitor delegate;

    @Inject
    public AnthropicConnectionMonitor(Clock clock) {
        this.delegate = new ConnectionMonitor(DEPENDENCY, Objects.requireNonNull(clock, "clock"));
    }

    public void recordSuccess() {
        delegate.recordSuccess();
    }

    public void recordError(Throwable cause) {
        delegate.recordError(cause);
    }

    public boolean shouldShortCircuit() {
        return delegate.shouldShortCircuit();
    }

    public void admitProbe() {
        delegate.admitProbe();
    }

    public ConnectionMonitor.State state() {
        return delegate.state();
    }

    public String dependency() {
        return delegate.dependency();
    }
}
