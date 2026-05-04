// =============================================================================
// api-gateway-bff — edge Spring Boot 3.x Backend-for-Frontend (Phase 6).
//
// Why Spring Boot, not Quarkus: per CLAUDE.md tech stack ("Spring Boot 3.x for
// cold-path services") and architecture-spec §9 (BFF is Tier 2 edge). The
// workload is a small fan-out per Angular dashboard request (4 downstream
// REST calls + a 5th aggregation). Spring's RestClient + a per-tenant
// in-memory bucket4j rate limiter cover Phase A; Phase 7 hands the rate-limit
// off to APIM.
//
// IMPORTANT: this is a SERVLET-stack Spring Boot service (NOT WebFlux / Spring
// Cloud Gateway). The Phase 0 skeleton was reactive-only (spring-cloud-starter-
// gateway pulls in WebFlux); Phase 6 replaces that with vanilla spring-boot-
// starter-web because:
//   1) The BFF is a thin proxy + aggregator, not a full programmable gateway.
//      We don't need predicates, route filters, or Lua-style mutation.
//   2) Spring's RestClient (synchronous, available since Boot 3.2) is plenty
//      fast for 4 fan-out calls in CompletableFuture.supplyAsync, and
//      OncePerRequestFilter for auth + rate-limit is far simpler than
//      WebFilter chains in WebFlux.
//   3) The Angular dashboard hits the BFF at request rates measured in
//      "few per minute" (operator usage); blocking IO is fine.
//
// Phase B JWT validation: spring-boot-starter-oauth2-resource-server is
// declared but UNUSED at runtime (the Auth0JwtFilter is behind the
// `levelsweep.feature-flags.phase-b-jwt-auth` flag, default OFF). Bringing
// the starter in now keeps the dependency-set stable across the Phase A→B
// flip (no rebuild required to enable the flag).
//
// Build outputs the bootJar under build/libs/api-gateway-bff-*.jar. The
// Dockerfile.jvm consumes that jar directly. No native-image build —
// per CLAUDE.md tech stack, native-image is for hot-path Quarkus services
// only.
// =============================================================================
plugins {
    java
    alias(libs.plugins.springBoot)
    alias(libs.plugins.springDependencyMgmt)
}

dependencies {
    implementation(project(":shared:shared-domain"))
    implementation(project(":shared:shared-tenant"))

    // --- Web + REST (servlet stack — see header comment) ---
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)

    // --- Phase B JWT validation (UNUSED at runtime; behind feature flag) ---
    // Kept in the dep set so the Phase A→B flip is a config change, not a
    // build change. See auth/Auth0JwtFilter for the wiring shape.
    implementation(libs.spring.boot.starter.oauth2.resource.server)

    // --- Per-tenant rate limit: in-process token bucket via bucket4j ---
    // Phase A keeps the limiter in-memory (single-replica BFF deployment per
    // architecture-spec §9 — one pod, one map). Phase 7 swaps the bucket
    // store for an APIM-backed external limiter, the policy stays the same:
    // 100 req/min/tenant, 429 + Retry-After:60 on breach.
    implementation("com.bucket4j:bucket4j-core:8.10.1")

    // --- Observability (Prometheus scrape via /actuator/prometheus) ---
    implementation(libs.micrometer.prometheus)

    // NOTE: do NOT pull libs.bundles.common.jackson here — that bundle pins
    // jackson-databind 2.18.1, which Spring Boot 3.3.5's BOM does NOT manage
    // (Boot 3.3.x ships jackson-bom 2.17.x). Mixing 2.18 databind with 2.17
    // jackson-core breaks at runtime with NoSuchMethodError. Same gotcha
    // documented in journal-service / user-config-service. Let the Spring
    // Boot BOM manage the jackson stack as a coherent set.
    implementation(libs.bundles.common.logging)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
}

tasks.named<Test>("test") {
    // Pin the `test` profile so application-test.yml's overrides (downstream
    // URLs pointing at fake hosts, JWT issuer-uri masked) take effect before
    // Spring Boot probes for real services.
    systemProperty("spring.profiles.active", "test")
}

tasks.register("nativeBuild") {
    group = "build"
    description = "Edge service — native-image is not on the Phase 7 plan."
    doLast { logger.lifecycle("[nativeBuild] no-op for api-gateway-bff (edge Spring Boot).") }
}
