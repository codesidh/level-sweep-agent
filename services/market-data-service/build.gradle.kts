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

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.rest.assured)
    testImplementation(libs.assertj.core)
}

// Native-image profile stub (Phase 7 — do not run in Phase 0)
tasks.register("nativeBuild") {
    group = "build"
    description = "TODO: enable Quarkus native compile in Phase 7."
    doLast {
        logger.lifecycle(
            "[nativeBuild] stub — wire `./gradlew :services:market-data-service:build -Dquarkus.package.type=native` in Phase 7.",
        )
    }
}
