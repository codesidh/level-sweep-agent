plugins {
    `java-library`
}

dependencies {
    api(libs.jakarta.validation)
    api(libs.bundles.common.jackson)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.testing.junit)
}
