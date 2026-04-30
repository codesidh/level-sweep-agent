Gradle Wrapper jar generation
=============================

The Gradle Wrapper jar (`gradle-wrapper.jar`) is a binary file and is NOT
committed by the scaffolding script. After cloning, run ONCE:

    gradle wrapper --gradle-version 8.10.2 --distribution-type bin

That command requires a system-installed Gradle (any 8.x). It will populate
`gradle/wrapper/gradle-wrapper.jar` to match `gradle-wrapper.properties` in
this directory. Subsequent `./gradlew` invocations will then work normally
on any machine without a system Gradle install.

Verify with:

    ./gradlew --version

Expected:
    Gradle 8.10.2

If `gradle` is not on PATH, install via your package manager (brew/scoop/sdkman)
or download from https://gradle.org/releases/ and run the wrapper command from
that distribution.
