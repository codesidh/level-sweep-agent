package com.levelsweep.userconfig;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Trivial sanity test — confirms the test runner picks up Spring Boot 3.x
 * compile output and the JUnit 5 platform is wired correctly. Phase 6
 * intentionally avoids {@code @SpringBootTest} here: it would auto-configure
 * a real DataSource and run Flyway migrations, both of which require external
 * dependencies that the unit-test profile must not need.
 *
 * <p>The slice tests ({@link com.levelsweep.userconfig.api.TenantConfigControllerTest},
 * {@link com.levelsweep.userconfig.store.TenantConfigRepositoryTest},
 * {@link com.levelsweep.userconfig.bootstrap.OwnerSeedTest}) cover the
 * controller and persistence paths with mocks. A full {@code @SpringBootTest}
 * smoke test against testcontainers MS SQL is a Phase 7 follow-up once the
 * integration-test Gradle convention is wired across services.
 */
class SmokeTest {

    @Test
    void truthIsTrue() {
        assertThat(true).isTrue();
    }
}
