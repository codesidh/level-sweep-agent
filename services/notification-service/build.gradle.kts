// =============================================================================
// notification-service — cold-path Spring Boot 3.x Kafka fan-out (Phase 6).
//
// Why Spring Boot, not Quarkus: per CLAUDE.md tech stack ("Spring Boot 3.x for
// cold-path services") and architecture-spec §9 (Notification Service is Tier
// 2 cold). Workload is steady-state Kafka consume → SMTP send → Mongo audit
// — no benefit from Quarkus' fast-startup + native-image strengths, while
// spring-kafka + spring-boot-starter-mail + Spring Data MongoDB give us
// idiomatic infra in a single starter set.
//
// Mirrors the journal-service build pattern one-for-one — same plugin set,
// same starter shape, same `tasks.named<Test>("test")` test-profile pin so
// unit tests never connect to a real Kafka / Mongo / SMTP host.
//
// Build outputs the bootJar under build/libs/notification-service-*.jar.
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

    // --- Web + actuator (Prometheus scrape, K8s liveness/readiness probes) ---
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)

    // --- Kafka consumer for the `notifications` topic (architecture-spec §12.1) ---
    implementation(libs.spring.kafka)

    // --- Mongo for the per-delivery audit log (notifications.outbox) ---
    implementation(libs.spring.boot.starter.data.mongodb)

    // --- SMTP delivery channel — JavaMailSender + Jakarta Mail under the hood ---
    implementation(libs.spring.boot.starter.mail)

    // --- Observability ---
    implementation(libs.micrometer.prometheus)

    // NOTE: do NOT pull libs.bundles.common.jackson here — same rationale as
    // journal-service: Spring Boot 3.3.5's BOM manages a coherent jackson stack
    // (2.17.x); pinning 2.18.x via the bundle breaks at runtime with
    // NoSuchMethodError on StreamReadConstraints.
    implementation(libs.bundles.common.logging)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
}

tasks.named<Test>("test") {
    // Force the %test profile so application.yml's test-profile overrides
    // (empty Kafka bootstrap, Mongo URI pointed at a fake host, SMTP host
    // empty so EmailDispatcher logs-only) take effect before any Mockito-only
    // test even tries to construct a producer/template/JavaMailSender.
    systemProperty("spring.profiles.active", "test")
}

tasks.register("nativeBuild") {
    group = "build"
    description = "Cold-path service — native-image is not on the Phase 7 plan."
    doLast { logger.lifecycle("[nativeBuild] no-op for notification-service (cold-path Spring Boot).") }
}
