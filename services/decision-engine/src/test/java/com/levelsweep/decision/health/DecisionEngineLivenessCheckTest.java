package com.levelsweep.decision.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.decision.ingest.BarConsumer;
import com.levelsweep.decision.ingest.NoOpBarRouter;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit unit tests for {@link DecisionEngineLivenessCheck}. Avoids
 * {@code @QuarkusTest} so the suite stays cheap on CI.
 */
class DecisionEngineLivenessCheckTest {

    @Test
    void livenessUpWhenConsumerWired() {
        BarConsumer consumer = new BarConsumer(new NoOpBarRouter());

        HealthCheckResponse response = new DecisionEngineLivenessCheck(consumer).call();

        assertThat(response.getName()).isEqualTo("decision-engine-pipeline");
        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
    }

    @Test
    void livenessDownWhenConsumerNull() {
        // The CDI runtime never passes null, but the defensive check is worth its weight.
        HealthCheckResponse response = new DecisionEngineLivenessCheck(null).call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        assertThat(response.getData()).isPresent();
        assertThat(response.getData().get()).containsEntry("reason", "consumer-not-wired");
    }
}
