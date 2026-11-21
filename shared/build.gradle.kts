import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidLibrary)
}

val personalPresetExport = rootProject.layout.projectDirectory.file("ml/export/personal_presets.json")
val personalPresetSources = layout.buildDirectory.dir("generated/personalPresets/kotlin")
val generatePersonalPresets by tasks.registering {
    inputs.file(personalPresetExport).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(personalPresetSources)

    doLast {
        // Embed the same preset export in both targets without platform resource loading.
        val source = personalPresetSources.get().file("PersonalPresetsExport.kt").asFile
        source.parentFile.mkdirs()
        val quotes = "\"\"\""
        source.writeText(
            "package ch.ciuin.shared.personal\n\n" +
                "internal const val personalPresetsJson = $quotes\n" +
                personalPresetExport.asFile.readText().trim() + "\n" +
                "$quotes\n",
        )
    }
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop")

    sourceSets {
        commonMain {
            kotlin.srcDir(generatePersonalPresets)
        }
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)
            }
        }
    }
}

tasks.named("desktopTest") {
    inputs.file(rootProject.layout.projectDirectory.file("ml/export/personal_model_test.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

val desktopMainCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
val replayWavPath = providers.gradleProperty("wav")
tasks.register<JavaExec>("replayStage0Wav") {
    group = "application"
    description = "Report stage 0 audio metrics for the WAV file provided via -Pwav."
    mainClass.set("ch.ciuin.shared.audio.Stage0WavReplayKt")
    classpath(desktopMainCompilation.output.allOutputs, desktopMainCompilation.runtimeDependencyFiles)
    workingDir(rootDir)
    argumentProviders.add(CommandLineArgumentProvider { listOfNotNull(replayWavPath.orNull) })
}

android {
    namespace = "ch.ciuin.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
