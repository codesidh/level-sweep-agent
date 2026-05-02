package com.levelsweep.aiagent.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.levelsweep.aiagent.anthropic.AnthropicClient;
import com.levelsweep.aiagent.cost.DailyCostTracker;
import com.mongodb.client.MongoClient;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit unit tests for {@link AiAgentReadinessCheck}. Mirrors execution-service's
 * {@code ExecutionServiceReadinessCheckTest}.
 */
class AiAgentReadinessCheckTest {

    @Test
    @SuppressWarnings("unchecked")
    void readinessUpWithMongoBound() {
        AnthropicClient client = mock(AnthropicClient.class);
        DailyCostTracker tracker = mock(DailyCostTracker.class);
        Instance<MongoClient> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(false);

        HealthCheckResponse response = new AiAgentReadinessCheck(client, instance, tracker).call();

        assertThat(response.getName()).isEqualTo("ai-agent-service-readiness");
        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(response.getData()).isPresent();
        assertThat(response.getData().get()).containsEntry("mode", "substrate-only");
        assertThat(response.getData().get()).containsEntry("mongo", "bound");
    }

    @Test
    @SuppressWarnings("unchecked")
    void readinessUpInStubMongoMode() {
        AnthropicClient client = mock(AnthropicClient.class);
        DailyCostTracker tracker = mock(DailyCostTracker.class);
        Instance<MongoClient> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(true);

        HealthCheckResponse response = new AiAgentReadinessCheck(client, instance, tracker).call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(response.getData().get()).containsEntry("mongo", "stub");
    }

    @Test
    @SuppressWarnings("unchecked")
    void readinessDownWhenClientNull() {
        DailyCostTracker tracker = mock(DailyCostTracker.class);
        Instance<MongoClient> instance = mock(Instance.class);

        HealthCheckResponse response = new AiAgentReadinessCheck(null, instance, tracker).call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        assertThat(response.getData().get()).containsEntry("reason", "anthropic-client-not-wired");
    }

    @Test
    @SuppressWarnings("unchecked")
    void readinessDownWhenTrackerNull() {
        AnthropicClient client = mock(AnthropicClient.class);
        Instance<MongoClient> instance = mock(Instance.class);

        HealthCheckResponse response = new AiAgentReadinessCheck(client, instance, null).call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        assertThat(response.getData().get()).containsEntry("reason", "cost-tracker-not-wired");
    }
}
