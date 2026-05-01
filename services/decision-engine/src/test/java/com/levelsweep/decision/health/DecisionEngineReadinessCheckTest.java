package com.levelsweep.decision.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit unit tests for {@link DecisionEngineReadinessCheck}.
 *
 * <p>Step 1 has no real downstream dep yet, so readiness is unconditionally UP
 * with a {@code mode=ingest-only} marker so operators can tell what phase the
 * service is at via {@code /q/health/ready}.
 */
class DecisionEngineReadinessCheckTest {

    @Test
    void readinessAlwaysUpWithIngestOnlyMode() {
        HealthCheckResponse response = new DecisionEngineReadinessCheck().call();

        assertThat(response.getName()).isEqualTo("decision-engine-readiness");
        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(response.getData()).isPresent();
        assertThat(response.getData().get()).containsEntry("mode", "ingest-only");
    }
}
