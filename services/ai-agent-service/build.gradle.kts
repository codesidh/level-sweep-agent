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
    implementation(libs.quarkus.mongodb.client)
    implementation(libs.quarkus.smallrye.fault.tolerance)

    // Anthropic Java SDK: per ADR-0006 we hand-roll a JDK HttpClient against
    // the public Messages REST API rather than pulling LangChain4J / Spring AI
    // / a future first-party SDK. Mirrors AlpacaTradingClient's pattern.
    //
    // implementation(libs.anthropic.sdk) — explicitly NOT pulled.

    testImplementation(libs.quarkus.junit5)
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
