package com.levelsweep.aiagent.health;

import com.levelsweep.aiagent.anthropic.AnthropicClient;
import com.levelsweep.aiagent.cost.DailyCostTracker;
import com.mongodb.client.MongoClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness probe for ai-agent-service.
 *
 * <p>Phase 4 Step 1 — the substrate that Trade Narrator + Daily Reviewer will
 * sit on. Until S2 wires the Narrator and S3 the Reviewer, "ready" reports the
 * three substrate beans:
 *
 * <ol>
 *   <li>{@link AnthropicClient} constructed (CDI bean wired). API key may be
 *       absent in dev — that's allowed (the smoke test is "the bean exists",
 *       not "Anthropic is reachable")</li>
 *   <li>{@link MongoClient} bound — or stub-mode, which is correct for the
 *       {@code %test} profile</li>
 *   <li>{@link DailyCostTracker} bootstrapped (caps loaded from config)</li>
 * </ol>
 *
 * <p>The {@code mode} field on the response distinguishes Step 1 from later
 * phases for operators inspecting {@code /q/health/ready}: today
 * {@code substrate-only}; later additions will report {@code narrating} /
 * {@code reviewing} / {@code sentinel-armed} / {@code assistant-online} as
 * those modules come online.
 *
 * <p>Endpoint: SmallRye exposes this at {@code /q/health/ready}. Mirrors
 * execution-service's {@code ExecutionServiceReadinessCheck}.
 */
@Readiness
@ApplicationScoped
public class AiAgentReadinessCheck implements HealthCheck {

    private static final String NAME = "ai-agent-service-readiness";

    @Inject
    AnthropicClient client;

    @Inject
    Instance<MongoClient> mongoClientInstance;

    @Inject
    DailyCostTracker costTracker;

    /** CDI no-arg constructor. */
    public AiAgentReadinessCheck() {
        // Intentionally empty.
    }

    /** Test seam — lets unit tests construct with hand-built collaborators. */
    AiAgentReadinessCheck(
            AnthropicClient client, Instance<MongoClient> mongoClientInstance, DailyCostTracker costTracker) {
        this.client = client;
        this.mongoClientInstance = mongoClientInstance;
        this.costTracker = costTracker;
    }

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder b = HealthCheckResponse.named(NAME).withData("mode", "substrate-only");
        if (client == null) {
            return b.down().withData("reason", "anthropic-client-not-wired").build();
        }
        if (costTracker == null) {
            return b.down().withData("reason", "cost-tracker-not-wired").build();
        }
        boolean mongoBound = mongoClientInstance != null && !mongoClientInstance.isUnsatisfied();
        b.withData("mongo", mongoBound ? "bound" : "stub");
        return b.up().build();
    }
}
