plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.shadow.media.reading"
    compileSdk = 36
    defaultConfig { minSdk = 26; targetSdk = 36; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("fixtures/reading"))
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(project(":core:library"))
    implementation(project(":core:network"))
    implementation("org.readium.kotlin-toolkit:readium-shared:3.3.0")
    implementation("org.readium.kotlin-toolkit:readium-streamer:3.3.0")
    implementation("org.readium.kotlin-toolkit:readium-navigator:3.3.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("com.davemorrissey.labs:subsampling-scale-image-view-androidx:3.10.0")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

// Optional real-server regressions must rerun when their external test inputs change.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    inputs.property("novelValidationPackages", providers.environmentVariable("NOVEL_VALIDATION_PACKAGES").orElse(""))
    inputs.property("nativeReaderScreenshots", providers.environmentVariable("NATIVE_READER_SCREENSHOTS").orElse(""))
    inputs.property("novelValidationUrl", providers.environmentVariable("NOVEL_VALIDATION_URL").orElse(""))
    inputs.property("novelValidationTargets", providers.environmentVariable("NOVEL_VALIDATION_TARGETS").orElse(""))
}
