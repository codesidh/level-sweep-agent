package com.levelsweep.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Trivial sanity test — confirms the test runner picks up Spring Boot 3.x
 * compile output and the JUnit 5 platform is wired correctly. Phase 6
 * intentionally avoids {@code @SpringBootTest} here: it would auto-configure
 * a real Mongo client, a real Kafka consumer factory, and a real
 * {@code JavaMailSender}, all of which require external dependencies that
 * the unit-test profile must not need.
 *
 * <p>The slice tests ({@code NotificationConsumerTest},
 * {@code EmailDispatcherTest}, {@code NotificationDispatcherTest},
 * {@code NotificationOutboxRepositoryTest}, plus the
 * {@code NotificationEventTest} in shared-domain) cover the consumer,
 * dispatch, and Mongo-write paths with mocks. A full
 * {@code @SpringBootTest} smoke test against testcontainers Mongo + an
 * embedded Kafka broker is a Phase 7 follow-up once the integration-test
 * Gradle convention is wired across services.
 */
class SmokeTest {

    @Test
    void truthIsTrue() {
        assertThat(true).isTrue();
    }
}
