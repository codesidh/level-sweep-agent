package com.levelsweep.aiagent.health;

import com.levelsweep.aiagent.anthropic.AnthropicClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Liveness;

/**
 * Liveness probe for ai-agent-service. UP whenever the {@link AnthropicClient}
 * bean is wired — i.e., the {@code @ApplicationScoped} construction completed.
 *
 * <p>K8s restarts the pod on liveness failure, so this check is intentionally
 * conservative. A wedged JVM fails the probe by HTTP timeout, which K8s already
 * handles. Outbound Anthropic CB state is the readiness probe's concern, not
 * liveness.
 *
 * <p>Endpoint: SmallRye exposes this at {@code /q/health/live} (architecture-spec
 * §17.4 / §21.1). Mirrors execution-service's
 * {@code ExecutionServiceLivenessCheck}.
 */
@Liveness
@ApplicationScoped
public class AiAgentLivenessCheck implements HealthCheck {

    private static final String NAME = "ai-agent-service-pipeline";

    @Inject
    AnthropicClient client;

    /** CDI no-arg constructor. */
    public AiAgentLivenessCheck() {
        // Intentionally empty — fields populated via CDI field injection.
    }

    /** Test seam — lets unit tests construct the check with a hand-built client. */
    AiAgentLivenessCheck(AnthropicClient client) {
        this.client = client;
    }

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder b = HealthCheckResponse.named(NAME);
        if (client == null) {
            // Defensive: with @ApplicationScoped, this branch is unreachable in a
            // properly wired Quarkus runtime — CDI throws at injection time if the
            // bean cannot be constructed.
            return b.down().withData("reason", "anthropic-client-not-wired").build();
        }
        return b.up().build();
    }
}
