// =============================================================================
// projection-service — cold-path Spring Boot 3.x Monte Carlo projection engine
// (Phase 6).
//
// Why Spring Boot, not Quarkus: the projection service is the cold-path read
// model that runs Monte Carlo equity-curve simulations on demand and caches
// the results in Mongo. Per CLAUDE.md tech stack ("Spring Boot 3.x for
// cold-path services") and architecture-spec §9 (Projection Service is Tier 2
// cold-path), the workload is REST request/response with one heavy CPU burst
// per call — Quarkus' fast-startup + native-image strengths bring no benefit
// here, while Spring Data MongoDB + Bean Validation + Actuator give us
// idiomatic infra in a single starter set.
//
// Build outputs the bootJar under build/libs/projection-service-*.jar. The
// Dockerfile.jvm consumes that jar directly — no fast-jar layout, no
// quarkus-run.jar wrapper. ENTRYPOINT is `java -jar /app.jar`.
// =============================================================================
plugins {
    java
    alias(libs.plugins.springBoot)
    alias(libs.plugins.springDependencyMgmt)
}

dependencies {
    implementation(project(":shared:shared-domain"))
    implementation(project(":shared:shared-tenant"))

    // --- Web + REST (POST /projection/run, GET /projection/last/{tenantId}) ---
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)

    // --- Mongo cache for projection runs (projections.runs) ---
    implementation(libs.spring.boot.starter.data.mongodb)

    // --- Observability (Prometheus scrape via /actuator/prometheus) ---
    implementation(libs.micrometer.prometheus)

    // NOTE: do NOT pull libs.bundles.common.jackson here — that bundle pins
    // jackson-databind 2.18.1, which Spring Boot 3.3.5's BOM does NOT manage
    // (Boot 3.3.x ships jackson-bom 2.17.x). Mixing 2.18 databind with 2.17
    // jackson-core breaks at runtime with NoSuchMethodError on
    // `StreamReadConstraints` because the databind 2.18 binary calls a
    // jackson-core 2.18 constructor. Let the Spring Boot BOM manage the
    // jackson stack as a coherent set. Mirrors journal-service.
    implementation(libs.bundles.common.logging)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
}

// Spring Boot bootJar is the standard packaging — Spring Boot plugin wires it
// automatically. Phase 7 native-image conversion is on the Quarkus services
// only; cold-path Spring Boot stays on the JVM image (eclipse-temurin:21-jre).
tasks.named<Test>("test") {
    // Force the %test profile so application.yml's test-profile overrides
    // (Mongo URI pointed at a fake host) take effect before any Mockito-only
    // test even tries to construct a MongoTemplate.
    systemProperty("spring.profiles.active", "test")
}

tasks.register("nativeBuild") {
    group = "build"
    description = "Cold-path service — native-image is not on the Phase 7 plan."
    doLast { logger.lifecycle("[nativeBuild] no-op for projection-service (cold-path Spring Boot).") }
}
