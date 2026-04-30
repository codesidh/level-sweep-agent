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
    implementation(libs.quarkus.kafka.client)
    implementation(libs.quarkus.mongodb.client)
    implementation(libs.quarkus.jdbc.mssql)
    implementation(libs.quarkus.smallrye.fault.tolerance)

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.rest.assured)
    testImplementation(libs.assertj.core)
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
