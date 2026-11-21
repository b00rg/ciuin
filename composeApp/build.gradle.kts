import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

/** Backend URL: `-Pciuin.apiUrl=http://<laptop-ip>:8000` (or `ciuin.apiUrl=...` in ~/.gradle/gradle.properties). */
val apiUrl: String = providers.gradleProperty("ciuin.apiUrl").getOrElse("http://10.0.2.2:8000")
val desktopApiUrl: String = providers.gradleProperty("ciuin.apiUrl").getOrElse("http://localhost:8000")

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
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
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "ch.ciuin.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1"
        // 10.0.2.2 = the host machine from the Android emulator. For a real phone on the hotspot:
        //   ./gradlew :composeApp:installDebug -Pciuin.apiUrl=http://<laptop-ip>:8000
        buildConfigField("String", "API_URL", "\"$apiUrl\"")
    }
    buildFeatures { buildConfig = true }
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
        jvmArgs += "-Dciuin.apiUrl=$desktopApiUrl"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "ciuin"
            packageVersion = "1.0.0"
        }
    }
}
