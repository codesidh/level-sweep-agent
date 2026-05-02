package com.levelsweep.aiagent.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.levelsweep.aiagent.anthropic.AnthropicClient;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit unit tests for {@link AiAgentLivenessCheck}. Mirrors execution-service's
 * {@code ExecutionServiceLivenessCheckTest}.
 */
class AiAgentLivenessCheckTest {

    @Test
    void livenessUpWhenClientWired() {
        AnthropicClient client = mock(AnthropicClient.class);

        HealthCheckResponse response = new AiAgentLivenessCheck(client).call();

        assertThat(response.getName()).isEqualTo("ai-agent-service-pipeline");
        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
    }

    @Test
    void livenessDownWhenClientNull() {
        // The CDI runtime never passes null, but the defensive check is worth its weight.
        HealthCheckResponse response = new AiAgentLivenessCheck(null).call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        assertThat(response.getData()).isPresent();
        assertThat(response.getData().get()).containsEntry("reason", "anthropic-client-not-wired");
    }
}
