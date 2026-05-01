package com.levelsweep.decision.health;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness probe for decision-engine.
 *
 * <p>Phase 2 Step 1 has no real downstream dependency yet — the service consumes
 * bars and routes them through {@link com.levelsweep.decision.ingest.NoOpBarRouter}.
 * Until Step 3 wires the Trade Saga's MS SQL writes and Step 4 wires the Strike
 * Selector's Alpaca options-chain client, "ready" simply means "process is up".
 *
 * <p>The {@code mode} field on the response distinguishes this Step 1 phase from
 * later phases for operators inspecting {@code /q/health/ready}: today
 * {@code ingest-only}; later additions will report {@code signal} / {@code risk} /
 * {@code trading} as the corresponding modules come online.
 *
 * <p>Endpoint: SmallRye exposes this at {@code /q/health/ready}.
 */
@Readiness
@ApplicationScoped
public class DecisionEngineReadinessCheck implements HealthCheck {

    private static final String NAME = "decision-engine-readiness";

    /** CDI no-arg constructor (required for @ApplicationScoped beans). */
    public DecisionEngineReadinessCheck() {
        // Intentionally empty.
    }

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named(NAME)
                .up()
                .withData("mode", "ingest-only")
                .build();
    }
}
