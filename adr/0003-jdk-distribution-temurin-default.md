# ADR-0003: JDK distribution — Temurin default; GraalVM only at Phase 7

**Status**: accepted
**Date**: 2026-04-30
**Deciders**: owner

## Context

`architecture-spec.md` §15 (Tech Stack) specifies JDK 21 LTS with virtual threads + ZGC, and **GraalVM native image** for hot-path services (Market Data, Decision Engine, Execution). Two JDK distributions are in play:

- **Eclipse Temurin (HotSpot)** — standard JVM; drop-in for any JDK 21 use
- **GraalVM (CE or Oracle)** — provides `native-image` for AOT compilation; can also run as a regular JIT JVM

Open question (Subagent A): pin GraalVM in CI from Phase 0, or stick with Temurin and only switch to GraalVM in Phase 7 (Resilience hardening, when native images become real)?

## Decision

**Default to Temurin 21 everywhere; introduce GraalVM only in Phase 7 for the hot-path native build job.**

- **Local dev**: Temurin 21 via SDKMAN / asdf / homebrew / installer (no GraalVM dependency)
- **CI (GitHub Actions)**: `actions/setup-java@v4` with `distribution: temurin`, `java-version: 21`
- **Phase 7+**: add a separate matrix job using `graalvm/setup-graalvm@v1` for the `nativeBuild` task on hot-path modules; keep the Temurin job as the default unit-/integration-test runner

## Consequences

- **Positive**: faster onboarding (one fewer thing to install), faster CI (Temurin starts quicker), clear phase boundary for native-image complexity, no need to debug native-image reflection / build-time init issues during strategy implementation.
- **Negative**: when Phase 7 begins, must validate that all hot-path code paths actually work as native (reflection config, build-time vs runtime init, resource inclusion). Surprises possible.
- **Mitigation**: each Quarkus hot-path module's `build.gradle.kts` carries the `quarkus-native-image` extension so `./gradlew :decision-engine:build -Dquarkus.native.enabled=true` smoke-builds can run any time on demand, even without CI integration.

## Alternatives Considered

- **GraalVM everywhere from Phase 0**: rejected — premature complexity; native-image quirks would slow strategy implementation.
- **Mix per service from day one**: rejected — operationally confusing while the team is still 1 engineer.

## References

- `architecture-spec.md` §15 (Tech Stack)
- `architecture-spec.md` §21 Phase 7 (Resilience hardening)
- `README.md` (quick-start)
