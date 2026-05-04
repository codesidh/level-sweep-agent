package com.levelsweep.projection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Trivial sanity test — confirms the test runner picks up Spring Boot 3.x
 * compile output and the JUnit 5 platform is wired correctly. Phase 6
 * intentionally avoids {@code @SpringBootTest} here: it would auto-configure
 * a real MongoTemplate which then tries to connect to the (deliberately fake)
 * test-profile Mongo URI on startup.
 *
 * <p>The slice tests ({@link com.levelsweep.projection.api.ProjectionControllerTest},
 * {@link com.levelsweep.projection.cache.ProjectionRunRepositoryTest},
 * {@link com.levelsweep.projection.engine.MonteCarloEngineTest},
 * {@link com.levelsweep.projection.api.ProjectionRequestHasherTest}) cover the
 * controller, persistence, engine, and hasher with mocks. A full
 * {@code @SpringBootTest} smoke test against a Mongo testcontainer is a
 * Phase 7 follow-up once the integration-test Gradle convention is wired
 * across services.
 */
class SmokeTest {

    @Test
    void truthIsTrue() {
        assertThat(true).isTrue();
    }
}
