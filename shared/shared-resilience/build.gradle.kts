plugins {
    `java-library`
}

dependencies {
    api(platform(libs.resilience4j.bom))
    api(libs.bundles.common.resilience4j)
    api(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.testing.junit)
}
