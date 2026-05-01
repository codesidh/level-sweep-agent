package com.levelsweep.execution.health;

import com.levelsweep.execution.ingest.TradeProposedConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Liveness;

/**
 * Liveness probe for execution-service. UP whenever the
 * {@link TradeProposedConsumer} bean is wired — i.e., the
 * {@code trade-proposed-in} {@code @Incoming} channel validated at boot.
 *
 * <p>K8s restarts the pod on liveness failure, so this check is intentionally
 * conservative. A wedged JVM fails the probe by HTTP timeout, which K8s already
 * handles. The Reactive Messaging consumer threads are managed by the Quarkus
 * runtime; introspecting them from here would couple us to internal SmallRye
 * APIs without adding signal that K8s does not already get.
 *
 * <p>Endpoint: SmallRye exposes this at {@code /q/health/live} (architecture-spec
 * §17.4 / §21.1). Mirrors decision-engine's {@code DecisionEngineLivenessCheck}.
 */
@Liveness
@ApplicationScoped
public class ExecutionServiceLivenessCheck implements HealthCheck {

    private static final String NAME = "execution-service-pipeline";

    @Inject
    TradeProposedConsumer consumer;

    /** CDI no-arg constructor. */
    public ExecutionServiceLivenessCheck() {
        // Intentionally empty — fields populated via CDI field injection.
    }

    /** Test seam — lets unit tests construct the check with a hand-built consumer. */
    ExecutionServiceLivenessCheck(TradeProposedConsumer consumer) {
        this.consumer = consumer;
    }

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder b = HealthCheckResponse.named(NAME);
        if (consumer == null) {
            // Defensive: with @ApplicationScoped, this branch is unreachable in a
            // properly wired Quarkus runtime — CDI throws at injection time if the
            // bean cannot be constructed.
            return b.down().withData("reason", "consumer-not-wired").build();
        }
        return b.up().build();
    }
}
