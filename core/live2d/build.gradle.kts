plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
val sdk = rootProject.extra["cubismSdkDirectory"] as File
@Suppress("UNCHECKED_CAST")
val sampleModels = rootProject.extra["cubismSampleModels"] as List<String>
android {
    namespace = "com.elio.lifearchive.core.live2d"
    compileSdk = 36
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro"); testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].java.srcDir(File(sdk, "Framework/framework/src/main/java"))
    sourceSets["main"].assets.srcDir(File(sdk, "Framework/framework/src/main/assets"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/cubismAssets"))
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { jvmToolchain(17) }
dependencies {
    compileOnly(files(rootProject.extra["cubismCoreAar"]))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(files(rootProject.extra["cubismCoreAar"]))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
val prepareCubismAssets by tasks.registering(Sync::class) {
    dependsOn(rootProject.tasks.named("verifyCubismSdk"))
    sampleModels.forEach { model ->
        from(File(sdk, "Sample/src/main/assets/$model")) { into("live2d/$model") }
    }
    from(File(sdk, "LICENSE.md")) { into("live2d/licenses"); rename { "SDK-LICENSE.md" } }
    from(File(sdk, "Core/LICENSE.md")) { into("live2d/licenses"); rename { "Core-LICENSE.md" } }
    from(File(sdk, "Framework/LICENSE.md")) { into("live2d/licenses"); rename { "Framework-LICENSE.md" } }
    into(layout.buildDirectory.dir("generated/cubismAssets"))
}
tasks.named("preBuild") { dependsOn(prepareCubismAssets) }
