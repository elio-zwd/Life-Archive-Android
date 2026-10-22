plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
tasks.named<Test>("test") { outputs.file(layout.buildDirectory.file("reader-fixtures.json")) }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
