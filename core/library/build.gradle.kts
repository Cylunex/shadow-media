plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "top.cylunex.shadowmedia.library"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(project(":core:model"))
    api(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:provider"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.jsoup:jsoup:1.21.2")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.1.0")
}
