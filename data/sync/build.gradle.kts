plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "com.elio.lifearchive.data.sync"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":domain:sync"))
    implementation(project(":core:document"))
    api(project(":core:backup"))
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    testImplementation(project(":data:notes"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
}
tasks.withType<Test>().configureEach {
    val pcRoot = file(providers.environmentVariable("LIFE_ARCHIVE_PC_ROOT").getOrElse(rootProject.file("../life-archive-pc").absolutePath))
    systemProperty("lifeArchive.pcRoot", pcRoot.absolutePath)
    inputs.files(fileTree(pcRoot.resolve("src/simple-sync")), pcRoot.resolve("test/fixtures/simple-sync-server.mjs"))
}
