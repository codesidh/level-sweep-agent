plugins {
    java
    alias(libs.plugins.springBoot)
    alias(libs.plugins.springDependencyMgmt)
}

dependencies {
    implementation(project(":shared:shared-domain"))
    implementation(project(":shared:shared-tenant"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.mongodb)
    implementation(libs.spring.kafka)

    implementation(libs.bundles.common.jackson)
    implementation(libs.bundles.common.logging)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.assertj.core)
}
