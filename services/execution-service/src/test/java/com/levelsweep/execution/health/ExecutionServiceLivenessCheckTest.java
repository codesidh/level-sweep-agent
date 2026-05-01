package com.levelsweep.execution.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.execution.ingest.NoOpTradeRouter;
import com.levelsweep.execution.ingest.TradeProposedConsumer;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit unit tests for {@link ExecutionServiceLivenessCheck}. Avoids
 * {@code @QuarkusTest} so the suite stays cheap on CI. Mirrors decision-engine's
 * {@code DecisionEngineLivenessCheckTest}.
 */
class ExecutionServiceLivenessCheckTest {

    @Test
    void livenessUpWhenConsumerWired() {
        TradeProposedConsumer consumer = new TradeProposedConsumer(new NoOpTradeRouter());

        HealthCheckResponse response = new ExecutionServiceLivenessCheck(consumer).call();

        assertThat(response.getName()).isEqualTo("execution-service-pipeline");
        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
    }

    @Test
    void livenessDownWhenConsumerNull() {
        // The CDI runtime never passes null, but the defensive check is worth its weight.
        HealthCheckResponse response = new ExecutionServiceLivenessCheck(null).call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        assertThat(response.getData()).isPresent();
        assertThat(response.getData().get()).containsEntry("reason", "consumer-not-wired");
    }
}
