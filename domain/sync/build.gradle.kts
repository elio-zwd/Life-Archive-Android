plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":domain:notes"))
    implementation(libs.serialization.json)
    api(libs.coroutines.core)
    testImplementation(libs.junit)
}
