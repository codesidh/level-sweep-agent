import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    java
    alias(libs.plugins.springBoot)
    alias(libs.plugins.springDependencyMgmt)
}

// Spring Cloud BOM is needed for spring-cloud-starter-gateway version resolution.
// Phase 0 keeps the version literal here; promote to libs.versions.toml in Phase 6.
configure<DependencyManagementExtension> {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:2023.0.3")
    }
}

dependencies {
    implementation(project(":shared:shared-domain"))
    implementation(project(":shared:shared-tenant"))

    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)

    // Spring Cloud Gateway is reactive (WebFlux). Phase 0 — empty route table;
    // Phase 6 wires actual routes + JWT extraction + X-Tenant-Id propagation
    // per architecture-spec §16.4.
    implementation(libs.spring.cloud.starter.gateway)

    implementation(libs.bundles.common.jackson)
    implementation(libs.bundles.common.logging)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.assertj.core)
}
