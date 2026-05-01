package com.levelsweep.decision;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Boot-level smoke test — confirms Quarkus comes up under the {@code %test} profile
 * (Kafka swapped to in-memory, dev-services off) and the {@code tenant.id} config
 * resolves to the expected default. If this passes, every {@code @Incoming}-annotated
 * channel on {@link com.levelsweep.decision.ingest.BarConsumer} validated at build-time
 * and CDI wired the bean graph end-to-end (BarConsumer → BarRouter → NoOpBarRouter,
 * plus both health checks).
 */
@QuarkusTest
class SmokeTest {

    @Inject
    @ConfigProperty(name = "tenant.id")
    String tenantId;

    @Test
    void tenantIdResolvesToDefaultOwner() {
        assertThat(tenantId).isEqualTo("OWNER");
    }
}
