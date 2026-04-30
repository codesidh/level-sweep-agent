plugins {
    `java-library`
}

dependencies {
    api(project(":shared:shared-domain"))
    api(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.testing.junit)
}
