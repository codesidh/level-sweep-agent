pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.spring.io/release")
    }
    // Note: `libs` is auto-created from gradle/libs.versions.toml at the conventional path.
    // Do NOT add `versionCatalogs { create("libs") { from(...) } }` — Gradle 9 errors with
    // "you can only call the 'from' method a single time" because auto-load already
    // counts as one invocation.
}

rootProject.name = "level-sweep-agent"

// --- Shared modules (libraries) ---
include(":shared:shared-domain")
include(":shared:shared-tenant")
include(":shared:shared-fsm")
include(":shared:shared-resilience")

// --- Hot-path services (Quarkus) ---
include(":services:market-data-service")
include(":services:decision-engine")
include(":services:execution-service")
include(":services:ai-agent-service")

// --- Cold-path services (Spring Boot) ---
// Note: journal-service was originally Phase 0-skeletoned under Quarkus but
// Phase 6 ships it as Spring Boot 3.x per CLAUDE.md tech stack ("Spring Boot
// 3.x for cold-path services"). The CAP profile (architecture-spec §6) is
// "CP for write, AP for query" — long-running batch ingest, not a hot path,
// so Quarkus brings no benefit while spring-kafka + Spring Data MongoDB give
// us idiomatic infra in a single starter set.
include(":services:journal-service")
include(":services:user-config-service")
include(":services:projection-service")
include(":services:calendar-service")
include(":services:notification-service")

// --- Edge / BFF (Spring Boot) ---
include(":services:api-gateway-bff")
