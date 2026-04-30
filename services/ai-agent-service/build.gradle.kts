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

    // Anthropic Java SDK: no first-party SDK on Maven Central as of Phase 0.
    // Phase 4 (AI Agent Service implementation) decides between LangChain4J's
    // anthropic adapter, Spring AI's anthropic starter, or a hand-rolled
    // HTTP client against the public REST API. Track in ADR before wiring.
    // implementation(libs.anthropic.sdk)

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.rest.assured)
    testImplementation(libs.assertj.core)
}

tasks.register("nativeBuild") {
    group = "build"
    description = "TODO: enable Quarkus native compile in Phase 7."
    doLast { logger.lifecycle("[nativeBuild] stub for ai-agent-service.") }
}
