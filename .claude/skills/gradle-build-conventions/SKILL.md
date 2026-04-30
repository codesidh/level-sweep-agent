---
name: gradle-build-conventions
description: Conventions and known gotchas for the LevelSweepAgent Gradle multi-module build. Use when adding modules, modifying build.gradle.kts, settings.gradle.kts, libs.versions.toml, or wiring CI workflows that invoke Gradle. Triggers on Gradle, libs.versions.toml, build.gradle.kts, settings.gradle.kts, version catalog, gradle wrapper, spotless, ktlint, Quarkus plugin, Spring Boot plugin.
---

# Gradle Build Conventions

Hard-won conventions from Phase 0 CI debugging. Violating these silently breaks CI.

## Version catalog

1. `gradle/libs.versions.toml` is **auto-loaded** by Gradle 8+ as the `libs` catalog.
   Do **not** add `versionCatalogs { create("libs") { from(...) } }` in
   `settings.gradle.kts` — Gradle 9 errors with "you can only call the 'from'
   method a single time" because auto-load already counts as one invocation.

2. **Verify every new dependency coordinate exists on Maven Central before
   committing it to the catalog.** LLM-hallucinated artifact names are common
   (Phase 0 caught three: `com.anthropic:anthropic-java`, the wrong major of
   `alpaca-java`, and a non-existent `io.quarkus:quarkus-rest-assured`).

   Quick check before adding:
   ```bash
   curl -sS "https://search.maven.org/solrsearch/select?q=g:GROUP+AND+a:ARTIFACT&core=gav&rows=5&wt=json" \
     | jq '.response | {numFound, versions: [.docs[].v]}'
   ```

3. The catalog `restAssured` version is for the standalone `io.rest-assured:rest-assured`
   jar. Quarkus testing uses `quarkus-junit5` plus that jar; there is no
   `io.quarkus:quarkus-rest-assured` artifact.

## Gradle host pinning on CI

4. `gradle/actions/setup-gradle@v4` defaults to **latest Gradle**, which is
   currently 9.x. Quarkus 3.15.1 plugin is incompatible with Gradle 9
   ("Extending a detachedConfiguration is not allowed"). Always pin host
   Gradle to the wrapper version:
   ```yaml
   - uses: gradle/actions/setup-gradle@v4
     with:
       gradle-version: 8.10.2
   ```
   When upgrading Gradle, bump both wrapper and CI together.

## Spotless + ktlint

5. CI runs `./gradlew spotlessApply` (autofix) followed by a `git diff
   --exit-code` gate that excludes wrapper artifacts:
   ```bash
   git diff --exit-code -- ':!gradlew' ':!gradlew.bat' ':!gradle/wrapper/'
   ```
   The exclusions matter because `gradle wrapper` regenerates `gradlew` with
   mode 100755 + LF on Linux runners; the file was committed from Windows
   with mode 100644 + CRLF.

6. **Never fix a Spotless violation in a single file.** Run `./gradlew
   spotlessApply` over the whole tree, or adjust the rule globally via
   `.editorconfig`. Single-file fixes guarantee whack-a-mole on the next push.

7. `.editorconfig` rules in force:
   - `*.java` → max_line_length 120 (Palantir formatter enforces)
   - `*.{kt,kts}` → max_line_length 140 (Gradle DSL is verbose)
   - Trailing-comma rules on, autofixer applies them

## Spring Cloud version matrix (api-gateway-bff)

8. Spring Cloud BOM line tracks Spring Boot:
   - Spring Boot 3.2.x → Spring Cloud 2023.0.x
   - Spring Boot 3.3.x / 3.4.x → Spring Cloud 2024.0.x ← **we are here**
   - Spring Boot 3.5.x+ → Spring Cloud 2025.0.x
   Mismatching them silently downgrades Boot or fails to resolve transitive deps.

## Wrapper jar policy

9. `gradle/wrapper/gradle-wrapper.jar` is intentionally **not committed**;
   `.gitignore` excludes `*.jar`. CI regenerates it via the bootstrap step.
   Local devs run `gradle wrapper --gradle-version 8.10.2 --distribution-type bin`
   once after first clone (documented in `docs/local-dev.md` step 3).

## Per-service replay/native task overrides

10. Root `build.gradle.kts` registers no-op `replayTest` and `integrationTest`
    tasks at the `subprojects {}` level. Each service module overrides via
    `tasks.named("replayTest") { ... }` (see `services/decision-engine/build.gradle.kts`).
    Don't `tasks.register(...)` again at module level — it'll fail with
    "Cannot add task 'replayTest' as a task with that name already exists."

## Anti-patterns to flag

- Explicit `versionCatalogs { create("libs") { from(...) } }` in `settings.gradle.kts`
- `setup-gradle` without `gradle-version:` pin
- Hardcoded versions in module `build.gradle.kts` (use catalog)
- Single-file Spotless fixes
- Adding a dep without verifying its coordinate on Maven Central
- `git diff --exit-code` in CI without excluding wrapper artifacts
- Spring Cloud BOM version that doesn't match the Spring Boot line
