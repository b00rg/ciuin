import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val listenerAssets = layout.buildDirectory.dir("generated/listenerAssets")
val bundleListenerAssets by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("ml/export")) {
        include("audio-student-s.tflite", "cascade.json")
    }
    from(rootProject.layout.projectDirectory.file("data/processed/places_lausanne.geojson"))
    into(listenerAssets)
}
tasks.named("preBuild") { dependsOn(bundleListenerAssets) }

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.litert)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}

android {
    namespace = "ch.ciuin.app"
    sourceSets.getByName("main").assets.srcDir(listenerAssets)
    androidResources.noCompress += "tflite"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "ch.ciuin.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1"
        // Backend URL: -Pciuin.apiUrl=https://xyz.trycloudflare.com (or "fake").
        // Default 10.0.2.2 = the host machine as seen from the Android emulator.
        val apiUrl = (project.findProperty("ciuin.apiUrl") as String?) ?: "http://10.0.2.2:8000"
        buildConfigField("String", "API_URL", "\"$apiUrl\"")
        val walkingRouterUrl = providers.gradleProperty("ciuin.walkingRouterUrl").orNull
        val walkingRouterField = walkingRouterUrl?.let { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" } ?: "null"
        buildConfigField("String", "WALKING_ROUTER_URL", walkingRouterField)
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1,INDEX.LIST,io.netty.versions.properties}" }
    }
    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "ch.ciuin.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "ciuin"
            packageVersion = "1.0.0"
        }
    }
}
