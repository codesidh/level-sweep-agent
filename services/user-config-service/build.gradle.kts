// =============================================================================
// user-config-service — cold-path Spring Boot 3.x tenant configuration store
// (Phase 6).
//
// Why Spring Boot, not Quarkus: per CLAUDE.md tech stack ("Spring Boot 3.x for
// cold-path services") and architecture-spec §9, this service is the cold-path
// CRUD store of per-tenant configuration. The workload is a handful of REST
// reads/writes per session — Quarkus' fast-startup + native-image strengths
// bring no benefit here, while Spring Boot's JdbcTemplate + Flyway give us
// idiomatic MS SQL infra in a single starter set.
//
// Storage: MS SQL via Flyway-managed schema and pure JdbcTemplate (no JPA,
// no Spring Data JDBC repositories). Per architecture-spec §13.1 MS SQL is the
// "system of record for financial state" — tenant_configs sits alongside
// tenants / users / daily_state / trades / orders / positions / fills /
// risk_events / fsm_transitions / agent_decisions in the same DB. Mirrors the
// raw-JDBC pattern from execution-service's EodFlattenAuditRepository.
//
// NO Kafka. NO Mongo. The service serves three REST endpoints and seeds an
// OWNER row at startup; no event-driven write path.
//
// Build outputs the bootJar under build/libs/user-config-service-*.jar. The
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

    // --- Web + REST (CRUD at /config/{tenantId}) ---
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)

    // --- MS SQL persistence: pure JdbcTemplate (no Spring Data JDBC repos),
    // matching execution-service's EodFlattenAuditRepository pattern.
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.mssql.jdbc)

    // --- Schema migrations: Flyway with the MS SQL adapter. ---
    implementation(libs.flyway.core)
    implementation(libs.flyway.mssql)

    // NOTE: deliberately NOT pulling libs.bundles.common.jackson — Spring Boot
    // 3.3.5's BOM manages a coherent jackson stack and the project bundle pins
    // jackson-databind 2.18.1 which Boot 3.3.x's BOM does not manage (mixing
    // 2.18 databind with 2.17 core blows up at runtime with NoSuchMethodError
    // on StreamReadConstraints). Same rationale as journal-service.
    implementation(libs.bundles.common.logging)

    // --- Observability (Prometheus scrape via /actuator/prometheus) ---
    implementation(libs.micrometer.prometheus)

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
    // (in-memory H2-shaped settings disabled, Flyway off, no real DataSource)
    // take effect before Spring Boot tries to auto-configure a DataSource
    // against the absent MSSQL_URL.
    systemProperty("spring.profiles.active", "test")
}

tasks.register("nativeBuild") {
    group = "build"
    description = "Cold-path service — native-image is not on the Phase 7 plan."
    doLast { logger.lifecycle("[nativeBuild] no-op for user-config-service (cold-path Spring Boot).") }
}
