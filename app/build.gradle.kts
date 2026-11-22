plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.elio.lifearchive"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.elio.lifearchive"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "0.5.0-dev.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:live2d"))
    implementation(files(rootProject.extra["cubismCoreAar"]))
    implementation(project(":domain:ai"))
    implementation(project(":data:ai"))
    implementation(project(":domain:pet"))
    implementation(project(":data:pet"))
    implementation(project(":feature:pet"))
    implementation(project(":domain:jianyu"))
    implementation(project(":data:jianyu"))
    implementation(project(":feature:jianyu"))
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)
    implementation(project(":domain:sync"))
    implementation(project(":data:sync"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:notes"))
    implementation(project(":feature:editor"))
    implementation(libs.androidx.compose.foundation)
    implementation(project(":core:document"))
    implementation(project(":domain:notes"))
    implementation(project(":data:notes"))
    implementation(project(":core:preferences"))
    implementation(project(":core:backup"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(project(":core:designsystem"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
