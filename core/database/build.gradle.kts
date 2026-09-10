import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.androidx.room)
}

android {
    namespace = "top.cylunex.shadowmedia.database"
    compileSdk = 36

    defaultConfig { minSdk = 26; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

room { schemaDirectory("$projectDir/schemas") }

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:model"))
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    api(libs.paging.runtime)
    kapt(libs.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.room.testing)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(libs.kotlinx.coroutines.android)
}
