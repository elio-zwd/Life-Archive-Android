plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.elio.lifearchive.core.markdown"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/readerAssets"))
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core:document"))
    implementation(libs.androidx.webkit)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

val readerDirectory = rootProject.layout.projectDirectory.dir("reader")
val installReader by tasks.registering(Exec::class) {
    workingDir(readerDirectory)
    commandLine("cmd", "/c", "npm.cmd", "ci", "--ignore-scripts", "--no-audit", "--no-fund")
    inputs.files(readerDirectory.file("package.json"), readerDirectory.file("package-lock.json"))
    outputs.file(readerDirectory.file("node_modules/.package-lock.json"))
}
val readerAssets by tasks.registering(Exec::class) {
    dependsOn(installReader)
    workingDir(readerDirectory)
    commandLine("node", "sync-assets.mjs")
    inputs.files(readerDirectory.file("package-lock.json"), readerDirectory.file("sync-assets.mjs"))
    outputs.dir(layout.buildDirectory.dir("generated/readerAssets"))
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(readerAssets) }
tasks.matching { it.name.contains("Lint") || it.name.startsWith("lint") }.configureEach { dependsOn(readerAssets) }
