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
    // quarkus-kafka-client stays in place for low-level admin/clients
    // (e.g., manual AdminClient calls, idempotent producer configuration in
    // S2). The reactive messaging extension below is the primary @Incoming
    // consumer pathway for TradeProposed.
    implementation(libs.quarkus.kafka.client)
    implementation(libs.quarkus.messaging.kafka)
    // Phase 3 dev cluster has no Kafka — the %prod profile routes the
    // trade-proposed-in incoming channel to the in-memory connector so
    // consumer construction does not crash on broker DNS at boot. Same
    // pattern as decision-engine. Phase 6 Strimzi rollout can drop this dep
    // when the in-memory fallback is no longer used.
    implementation(libs.smallrye.messaging.inmemory)
    // S6 (EOD flatten) will use @Scheduled — bring the extension in now to
    // avoid a second build.gradle bump when that lands.
    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.jdbc.mssql)
    implementation(libs.quarkus.smallrye.fault.tolerance)

    // Alpaca Java SDK 1.6.0 in catalog is stale — only 10.0.x is on
    // Maven Central. Phase 3 (Execution Service) decides between bumping
    // to 10.0.1 (breaking API change vs 1.x), using a hand-rolled HTTP
    // client against Alpaca's public REST/WS, or alpaca-trade-api-java.
    // implementation(libs.alpaca.sdk)

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.rest.assured)
    testImplementation(libs.assertj.core)
    // Mockito for the consumer/router/health unit tests. Same coordinates
    // decision-engine and market-data-service use.
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
}

tasks.register("nativeBuild") {
    group = "build"
    description = "TODO: enable Quarkus native compile in Phase 7."
    doLast { logger.lifecycle("[nativeBuild] stub for execution-service.") }
}
