plugins {
    `java-library`
}

dependencies {
    api(project(":shared:shared-domain"))
    api(libs.auth0.jwt)
    api(libs.auth0.jwks)
    api(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.testing.junit)
}
