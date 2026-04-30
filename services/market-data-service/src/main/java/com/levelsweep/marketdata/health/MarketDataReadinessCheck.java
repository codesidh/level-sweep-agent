package com.levelsweep.marketdata.health;

import com.levelsweep.marketdata.alpaca.AlpacaConfig;
import com.levelsweep.marketdata.connection.ConnectionState;
import com.levelsweep.marketdata.live.LivePipeline;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness probe wired to the Connection FSM exposed by {@link LivePipeline}.
 *
 * <p>"Ready" semantics for market-data-service:
 *
 * <ul>
 *   <li><b>Idle mode</b> (blank Alpaca API key) — UP. The in-process replay path
 *       is functional; idle vs. live is observable via the {@code connection_state}
 *       gauge, not via this probe. Treating idle as not-ready would break local
 *       development and the dev/replay deployment profile.
 *   <li><b>Live mode</b> — UP unless the Connection FSM is {@link ConnectionState#UNHEALTHY}.
 *       {@code DEGRADED} is operational (transient errors below the circuit-breaker
 *       threshold); {@code RECOVERING} means we're admitting probes again. Only
 *       {@code UNHEALTHY} (circuit-breaker open) flips readiness DOWN, so K8s
 *       removes the pod from its Service endpoints.
 * </ul>
 *
 * <p>Endpoint binding: SmallRye exposes this at {@code /q/health/ready}.
 */
@Readiness
@ApplicationScoped
public class MarketDataReadinessCheck implements HealthCheck {

    private static final String NAME = "market-data-readiness";

    @Inject
    LivePipeline pipeline;

    @Inject
    AlpacaConfig cfg;

    /** CDI no-arg constructor. */
    public MarketDataReadinessCheck() {
        // Intentionally empty — fields populated via CDI field injection.
    }

    /** Test seam — lets unit tests construct the check with hand-built dependencies. */
    MarketDataReadinessCheck(LivePipeline pipeline, AlpacaConfig cfg) {
        this.pipeline = pipeline;
        this.cfg = cfg;
    }

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder b = HealthCheckResponse.named(NAME);
        ConnectionState state = pipeline.connectionMonitor().state();

        if (cfg.apiKey().isBlank()) {
            // Idle mode — by-design ready. The pipeline still serves the in-process
            // replay path; operators distinguish idle from live via the metrics gauge.
            return b.up().withData("mode", "idle")
                    .withData("connectionState", state.name())
                    .build();
        }

        if (state == ConnectionState.UNHEALTHY) {
            return b.down()
                    .withData("connectionState", "UNHEALTHY")
                    .withData("dependency", pipeline.connectionMonitor().dependency())
                    .withData("droppedCount", pipeline.tickRingBuffer().droppedCount())
                    .build();
        }

        return b.up().withData("mode", "live")
                .withData("connectionState", state.name())
                .withData("wsAttached", pipeline.wsAttached())
                .build();
    }
}
