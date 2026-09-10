import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "top.cylunex.shadowmedia"
    compileSdk = 36

    defaultConfig {
        applicationId = "top.cylunex.shadowmedia"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "1.1.1"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    sourceSets.getByName("benchmark").assets.srcDir(rootProject.file("fixtures/reading"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:provider"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:playback"))
    implementation(project(":core:library"))
    implementation(project(":experience:audio"))
    implementation(project(":experience:reading"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.media3.ui.compose.material3)
    implementation(libs.paging.compose)
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Resolves artifacts only. This task does not compile, package, sign or install an APK.
tasks.register("writeRuntimeInventory") {
    val output = layout.buildDirectory.file("reports/runtime-artifacts.tsv")
    outputs.file(output)
    doLast {
        val rows = listOf("releaseRuntimeClasspath", "coreLibraryDesugaring").flatMap { configuration -> configurations.getByName(configuration).incoming.artifactView {
            componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
        }.artifacts.artifacts }.distinctBy { it.id }
            .map { artifact ->
                val id = artifact.id.componentIdentifier as org.gradle.api.artifacts.component.ModuleComponentIdentifier
                "${id.group}:${id.module}:${id.version}\t${artifact.file.absolutePath}"
            }.sorted()
        output.get().asFile.apply { parentFile.mkdirs(); writeText(rows.joinToString("\n", postfix = "\n")) }
    }
}
