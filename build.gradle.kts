// =============================================================================
// LevelSweepAgent — root build script (Gradle Kotlin DSL)
// Java 21 LTS · Quarkus (hot path) · Spring Boot 3.x (cold path)
// =============================================================================

plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}

allprojects {
    group = "com.levelsweep"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "jacoco")
    apply(plugin = "com.diffplug.spotless")

    repositories {
        mavenCentral()
        maven("https://repo.spring.io/release")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(
            listOf(
                "-Xlint:all",
                "-Xlint:-serial",
                "-Xlint:-processing",
                "-parameters"
            )
        )
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    extensions.configure<JacocoPluginExtension> {
        toolVersion = "0.8.12"
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            palantirJavaFormat(rootProject.libs.versions.palantirJavaFormat.get())
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint()
        }
    }

    // Convention: replay tests live under src/replayTest/java in Decision Engine; placeholder no-op task here
    tasks.register("replayTest") {
        group = "verification"
        description = "Decision Engine replay parity tests (overridden by decision-engine module)."
        doLast { logger.lifecycle("[replayTest] no-op for ${project.path}") }
    }

    tasks.register("integrationTest") {
        group = "verification"
        description = "Integration tests against docker-compose stack (override per module)."
        doLast { logger.lifecycle("[integrationTest] no-op for ${project.path}") }
    }
}

// Convenience aggregator
tasks.register("ciCheck") {
    group = "verification"
    description = "Local CI: spotless + build + test for all modules."
    dependsOn(subprojects.map { it.tasks.named("spotlessCheck") })
    dependsOn(subprojects.map { it.tasks.named("build") })
}
