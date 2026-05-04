plugins {
    java
    alias(libs.plugins.quarkus)
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))

    implementation(project(":shared:shared-domain"))
    implementation(project(":shared:shared-tenant"))
    implementation(project(":shared:shared-resilience"))

    implementation(libs.quarkus.arc)
    implementation(libs.quarkus.rest)
    implementation(libs.quarkus.rest.jackson)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.kafka.client)
    // Phase 4 S2 Trade Narrator — consumes the tenant.fills topic via
    // @Incoming("trade-fills-in"). Same extension execution-service pulls for
    // its TradeProposed consumer. Kept narrow on purpose: the narrator is
    // the only Kafka consumer in ai-agent-service for Phase 4.
    implementation(libs.quarkus.messaging.kafka)
    // Phase 4 dev cluster has no Kafka — the %prod and %test profiles route
    // the trade-fills-in incoming channel to the in-memory connector so
    // consumer construction does not crash on broker DNS at boot. Same
    // pattern as execution-service's trade-proposed-in / decision-engine.
    implementation(libs.smallrye.messaging.inmemory)
    implementation(libs.quarkus.mongodb.client)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    // Phase 4 S3 Daily Reviewer — @Scheduled cron at 16:30 ET runs the
    // session reviewer. Same extension execution-service uses for the EOD
    // flatten cron at 15:55 ET.
    implementation(libs.quarkus.scheduler)

    // Anthropic Java SDK: per ADR-0006 we hand-roll a JDK HttpClient against
    // the public Messages REST API rather than pulling LangChain4J / Spring AI
    // / a future first-party SDK. Mirrors AlpacaTradingClient's pattern.
    //
    // implementation(libs.anthropic.sdk) — explicitly NOT pulled.

    testImplementation(libs.quarkus.junit5)
    // Phase 5 S6 — @InjectMock + @QuarkusTest harness for AssistantResource
    // REST tests. Provides io.quarkus.test.InjectMock.
    testImplementation(libs.quarkus.junit5.mockito)
    testImplementation(libs.quarkus.rest.assured)
    testImplementation(libs.assertj.core)
    // Mockito for the AnthropicClient/DailyCostTracker/AuditWriter unit tests.
    // Same coordinates execution-service / decision-engine / market-data-service use.
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
}

tasks.register("nativeBuild") {
    group = "build"
    description = "TODO: enable Quarkus native compile in Phase 7."
    doLast { logger.lifecycle("[nativeBuild] stub for ai-agent-service.") }
}
