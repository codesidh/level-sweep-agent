package com.levelsweep.execution.health;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness probe for execution-service.
 *
 * <p>Phase 3 Step 1 has no real downstream dependency yet — the service consumes
 * {@link com.levelsweep.shared.domain.trade.TradeProposed} events and routes
 * them through {@link com.levelsweep.execution.ingest.NoOpTradeRouter}. Until
 * Step 2 wires the Alpaca order-placement client and Step 3 wires the
 * trade-updates websocket fill listener, "ready" simply means "process is up".
 *
 * <p>The {@code mode} field on the response distinguishes this Step 1 phase from
 * later phases for operators inspecting {@code /q/health/ready}: today
 * {@code ingest-only}; later additions will report {@code placing-orders} /
 * {@code listening-fills} / {@code managing-positions} as the corresponding
 * modules come online.
 *
 * <p>Endpoint: SmallRye exposes this at {@code /q/health/ready}. Mirrors
 * decision-engine's {@code DecisionEngineReadinessCheck}.
 */
@Readiness
@ApplicationScoped
public class ExecutionServiceReadinessCheck implements HealthCheck {

    private static final String NAME = "execution-service-readiness";

    /** CDI no-arg constructor (required for @ApplicationScoped beans). */
    public ExecutionServiceReadinessCheck() {
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
