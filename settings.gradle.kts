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
    versionCatalogs {
        create("libs") {
            from(files("gradle/libs.versions.toml"))
        }
    }
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
include(":services:journal-service")

// --- Cold-path services (Spring Boot) ---
include(":services:user-config-service")
include(":services:projection-service")
include(":services:calendar-service")
include(":services:notification-service")

// --- Edge / BFF (Spring Boot) ---
include(":services:api-gateway-bff")
