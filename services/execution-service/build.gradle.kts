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
}

tasks.register("nativeBuild") {
    group = "build"
    description = "TODO: enable Quarkus native compile in Phase 7."
    doLast { logger.lifecycle("[nativeBuild] stub for execution-service.") }
}
