plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "top.cylunex.shadowmedia.audio"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(project(":core:library"))
    api(libs.media3.session)
    implementation(libs.media3.exoplayer)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
}
