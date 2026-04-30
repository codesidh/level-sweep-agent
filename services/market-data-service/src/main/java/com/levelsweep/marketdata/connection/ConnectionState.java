package com.levelsweep.marketdata.connection;

/**
 * Lifecycle states for an external dependency connection.
 *
 * <p>See architecture-spec.md §10.6 (Connection FSM). Each external dependency
 * (Polygon WS, Alpaca, Auth0, MS SQL, Mongo, Kafka, Anthropic) tracks its own
 * Connection FSM independently. Hot-path UNHEALTHY states cause the Risk FSM
 * to auto-HALT (fail-closed); Anthropic UNHEALTHY → Sentinel falls back to
 * ALLOW (fail-open for AI).
 *
 * <p>Allowed transitions:
 *
 * <pre>
 *   HEALTHY    --[3 errors / 30s]--&gt; DEGRADED
 *   DEGRADED   --[CB open]---------&gt; UNHEALTHY
 *   UNHEALTHY  --[half-open probe]-&gt; RECOVERING
 *   RECOVERING --[probe ok]--------&gt; HEALTHY
 *   any        --[probe fail]------&gt; (stays / regresses)
 * </pre>
 */
public enum ConnectionState {
    HEALTHY,
    DEGRADED,
    UNHEALTHY,
    RECOVERING
}
