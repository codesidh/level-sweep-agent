// =============================================================================
// calendar-service — cold-path Spring Boot 3.x trading-calendar service (Phase 6).
//
// Why Spring Boot, not Quarkus: per CLAUDE.md tech stack ("Spring Boot 3.x for
// cold-path services") and architecture-spec §9 (Calendar Service is Tier 2
// cold). The calendar is a small, slow-changing reference dataset served from
// memory — there's no hot-path latency budget here. Spring Cache + Spring MVC
// + jackson-dataformat-yaml give us idiomatic infra in a single starter set.
//
// No Mongo, no Kafka, no MS SQL. The calendar is loaded from YAML resources at
// startup (NYSE holidays + FOMC dates 2026-2030, hand-verified). This closes
// the Phase 1 alert KQL known-issue "holiday calendar not modeled — fires on
// US market holidays".
//
// Build outputs the bootJar under build/libs/calendar-service-*.jar. The
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

    // --- Web + REST (3 endpoints under /calendar) ---
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)

    // --- In-memory cache (Spring Cache abstraction; ConcurrentMapCache impl) ---
    // spring-context-support is pulled in transitively by spring-boot-starter;
    // @EnableCaching + @Cacheable wire the default ConcurrentMapCacheManager
    // when no other CacheManager bean is on the classpath. Adequate for Phase
    // A — the dataset is < 100 KiB and fits trivially in heap.
    implementation("org.springframework.boot:spring-boot-starter-cache")

    // --- YAML loading via Jackson (Spring Boot manages the version) ---
    // jackson-dataformat-yaml is the YAML parser; we drive it with an
    // explicit ObjectMapper(YAMLFactory) at startup rather than relying on
    // Spring Boot's @ConfigurationProperties YAML loader, because the
    // dataset is too structured (date, name, half-day flag, type enum) to
    // fit cleanly into a flat property tree.
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    // --- Observability (Prometheus scrape via /actuator/prometheus) ---
    implementation(libs.micrometer.prometheus)

    // NOTE: do NOT pull libs.bundles.common.jackson here — that bundle pins
    // jackson-databind 2.18.1, which Spring Boot 3.3.5's BOM does NOT manage
    // (Boot 3.3.x ships jackson-bom 2.17.x). Mixing 2.18 databind with 2.17
    // jackson-core breaks at runtime with NoSuchMethodError. Same gotcha
    // documented in journal-service/build.gradle.kts. Let the Spring Boot
    // BOM manage the jackson stack as a coherent set.
    implementation(libs.bundles.common.logging)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.assertj.core)
}

// Spring Boot bootJar is the standard packaging — the Spring Boot plugin
// wires it automatically. Phase 7 native-image conversion is on the Quarkus
// services only; cold-path Spring Boot stays on the JVM image
// (eclipse-temurin:21-jre).
tasks.register("nativeBuild") {
    group = "build"
    description = "Cold-path service — native-image is not on the Phase 7 plan."
    doLast { logger.lifecycle("[nativeBuild] no-op for calendar-service (cold-path Spring Boot).") }
}
