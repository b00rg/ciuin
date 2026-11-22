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

val coverageFiles = mapOf(
    "globalCoverageJson" to rootProject.layout.projectDirectory.file("data/processed/coverage_2deg.json"),
    "europeCoverageJson" to rootProject.layout.projectDirectory.file("data/processed/coverage_1deg.json"),
)
val coverageSources = layout.buildDirectory.dir("generated/coverage/kotlin")
val generateCoverage by tasks.registering {
    inputs.files(coverageFiles.values).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(coverageSources)

    doLast {
        val source = coverageSources.get().file("BundledCoverage.kt").asFile
        source.parentFile.mkdirs()
        source.writeText(buildString {
            appendLine("package ch.ciuin.shared.api")
            for ((name, file) in coverageFiles) {
                appendLine("internal fun $name(): String = buildString {")
                // Separate appends keep each constant below the JVM class-file string limit.
                for (chunk in file.asFile.readText().chunked(8_000)) {
                    val escaped = chunk.replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("$", "\\$").replace("\r", "\\r").replace("\n", "\\n")
                    appendLine("    append(\"$escaped\")")
                }
                appendLine("}")
            }
        })
    }
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop")
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            kotlin.srcDir(generatePersonalPresets)
            kotlin.srcDir(generateCoverage)
        }
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
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
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
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
