plugins {
    java
    alias(libs.plugins.quarkus)
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))

    implementation(project(":shared:shared-domain"))
    implementation(project(":shared:shared-tenant"))
    implementation(project(":shared:shared-fsm"))
    implementation(project(":shared:shared-resilience"))

    implementation(libs.quarkus.arc)
    implementation(libs.quarkus.rest)
    implementation(libs.quarkus.rest.jackson)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.prometheus)
    implementation(libs.quarkus.opentelemetry)
    // quarkus-kafka-client stays in place — leaves room for low-level admin/clients
    // (e.g., manual AdminClient calls or Streams in a later phase). The reactive
    // messaging extension below is the primary @Incoming consumer pathway.
    implementation(libs.quarkus.kafka.client)
    implementation(libs.quarkus.messaging.kafka)
    // Phase 2 dev cluster has no Kafka — the %prod profile routes the four bars-*
    // incoming channels to the in-memory connector so consumer construction does
    // not crash on broker DNS at boot. Same pattern as market-data-service.
    // Phase 6 Strimzi rollout can drop this dep when the in-memory fallback is
    // no longer used.
    implementation(libs.smallrye.messaging.inmemory)
    implementation(libs.quarkus.mongodb.client)
    implementation(libs.quarkus.agroal)
    implementation(libs.quarkus.jdbc.mssql)
    implementation(libs.quarkus.flyway)
    implementation(libs.flyway.mssql)
    implementation(libs.quarkus.smallrye.fault.tolerance)

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.rest.assured)
    testImplementation(libs.assertj.core)
    // Mockito is used by strike-selector and risk-FSM unit tests. Same
    // versioned coordinate market-data-service's AlpacaRestClientTest uses.
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
}

// Replay-parity test task — populated in Phase 2 alongside the Trade Saga
tasks.named("replayTest") {
    doLast {
        logger.lifecycle("[replayTest] Decision Engine replay parity tests run here in Phase 2.")
    }
}

tasks.register("nativeBuild") {
    group = "build"
    description = "TODO: enable Quarkus native compile in Phase 7."
    doLast { logger.lifecycle("[nativeBuild] stub for decision-engine.") }
}
