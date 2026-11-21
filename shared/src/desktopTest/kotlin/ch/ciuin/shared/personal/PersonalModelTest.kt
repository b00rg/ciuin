package ch.ciuin.shared.personal

import ch.ciuin.shared.model.FeatureVector
import ch.ciuin.shared.model.TrainingExample
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PersonalModelTest {
    // Read the Python exports directly from Gradle's working directory (shared/).
    private val exports = File("../ml/export")
    private val json = Json { ignoreUnknownKeys = true }
    private val fixture by lazy {
        json.decodeFromString<ModelFixture>(exports.resolve("personal_model_test.json").readText())
    }
    private val presetsById = PersonalPreset.entries.associateBy { it.id }

    @Test
    fun runtimePresetsMatchExportAndFixture() {
        val export = json.decodeFromString<FixturePresetExport>(exports.resolve("personal_presets.json").readText())
        assertEquals(fixture.features, export.features)
        assertEquals(fixture.presets, export.presets)
        assertEquals(export.features, personalFeatureNames)
        assertEquals(export.presets.keys, presetsById.keys)

        for ((id, preset) in presetsById) {
            val weights = export.presets.getValue(id)
            val expectedTheta = listOf(weights.bias) + export.features.map { weights.weights.getValue(it) }
            assertVector(expectedTheta, LogisticPersonalModel(preset).theta, "$id initial theta")
        }
        assertVector(
            LogisticPersonalModel(PersonalPreset.AUTISM_FRIENDLY).theta.toList(),
            LogisticPersonalModel().theta,
            "default preset theta",
        )
    }

    @Test
    fun probeFeaturesMatchPythonTransforms() {
        for (user in fixture.users) {
            for (probe in user.probes) {
                assertVector(probe.x, featurize(probe.features), "${user.user} ${probe.scene} features")
            }
        }
    }

    @Test
    fun featureTransformClampsAndDefaultsMissingSoundClasses() {
        val belowRange = FeatureVector(25.0, 20.0, emptyMap(), -100.0, -0.1, -1)
        assertContentEquals(DoubleArray(14), featurize(belowRange))

        val aboveRange = FeatureVector(
            dbMean = 95.0,
            dbPeak = 130.0,
            soundClasses = mapOf("siren" to 1.2, "traffic" to -0.1),
            luxMean = 100_000.0,
            flickerIndex = 1.3,
            peopleCount = 100,
        )
        assertContentEquals(
            doubleArrayOf(1.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0),
            featurize(aboveRange),
        )
    }

    @Test
    fun updatesMatchPythonAtEveryCheckpoint() {
        for (user in fixture.users) {
            val model = LogisticPersonalModel(presetsById.getValue(user.preset))
            var updates = 0
            for (checkpoint in user.checkpoints) {
                while (updates < checkpoint.after) {
                    model.update(user.examples[updates])
                    updates++
                }
                assertCheckpoint(user, checkpoint, model)
            }
        }
    }

    @Test
    fun capDropsTheOldestExamples() {
        val cap = fixture.capTest
        val model = LogisticPersonalModel(presetsById.getValue(cap.preset), capacity = cap.hyperparameters.cap)
        for (example in cap.examples) {
            model.update(TrainingExample(example.features, example.label, "1970-01-01T00:00:00Z"))
        }

        assertVector(cap.expectedTheta, model.theta, "capped theta")
    }

    @Test
    fun defaultCapRetainsTheMostRecentExamples() {
        val cap = fixture.hyperparameters.cap
        val model = LogisticPersonalModel()
        val tailOnly = LogisticPersonalModel(PersonalPreset.AUTISM_FRIENDLY, capacity = cap)
        val examples = fixture.users.first().examples
        model.update(fixture.users.last().examples.first())
        repeat(cap) { index ->
            val example = examples[index % examples.size]
            model.update(example)
            tailOnly.update(example)
        }

        assertVector(tailOnly.theta.toList(), model.theta, "default capacity theta")
    }

    @Test
    fun resetRestoresTheSelectedPresetAndClearsExamples() {
        val user = fixture.users.first { it.preset != PersonalPreset.AUTISM_FRIENDLY.id }
        val model = LogisticPersonalModel(presetsById.getValue(user.preset))
        user.examples.forEach(model::update)

        model.reset()
        assertCheckpoint(user, user.checkpoints.single { it.after == 0 }, model)
        model.update(user.examples.first())
        assertCheckpoint(user, user.checkpoints.single { it.after == 1 }, model)
    }

    @Test
    fun invalidLabelLeavesTheModelUnchanged() {
        val user = fixture.users.first()
        val model = LogisticPersonalModel(presetsById.getValue(user.preset))
        val example = user.examples.first()

        assertFailsWith<IllegalArgumentException> { model.update(example.copy(label = 2)) }
        assertCheckpoint(user, user.checkpoints.single { it.after == 0 }, model)
        model.update(example)
        assertCheckpoint(user, user.checkpoints.single { it.after == 1 }, model)
    }

    private fun assertCheckpoint(user: SyntheticUser, checkpoint: Checkpoint, model: LogisticPersonalModel) {
        val context = "${user.user} after ${checkpoint.after} updates"
        assertVector(checkpoint.theta, model.theta, "$context theta")
        assertEquals(user.probes.size, checkpoint.probeScores.size, "$context probe count")
        user.probes.forEachIndexed { index, probe ->
            assertEquals(
                checkpoint.probeScores[index],
                model.score(probe.features),
                fixture.tolerance.score,
                "$context ${probe.scene} score",
            )
        }
    }

    private fun assertVector(expected: List<Double>, actual: DoubleArray, context: String) {
        assertEquals(expected.size, actual.size, "$context length")
        expected.forEachIndexed { index, value ->
            assertEquals(value, actual[index], fixture.tolerance.theta, "$context[$index]")
        }
    }
}

@Serializable
private data class ModelFixture(
    val hyperparameters: FixtureHyperparameters,
    val features: List<String>,
    val presets: Map<String, FixturePreset>,
    val tolerance: Tolerance,
    val users: List<SyntheticUser>,
    @SerialName("cap_test") val capTest: CapCase,
)

@Serializable
private data class FixturePresetExport(val features: List<String>, val presets: Map<String, FixturePreset>)

@Serializable
private data class FixturePreset(val bias: Double, val weights: Map<String, Double>)

@Serializable
private data class Tolerance(val theta: Double, val score: Double)

@Serializable
private data class SyntheticUser(
    val user: String,
    val preset: String,
    val examples: List<TrainingExample>,
    val probes: List<Probe>,
    val checkpoints: List<Checkpoint>,
)

@Serializable
private data class Probe(val scene: String, val features: FeatureVector, val x: List<Double>)

@Serializable
private data class Checkpoint(
    val after: Int,
    val theta: List<Double>,
    @SerialName("probe_scores") val probeScores: List<Double>,
)

@Serializable
private data class CapCase(
    val hyperparameters: FixtureHyperparameters,
    val preset: String,
    val examples: List<CapExample>,
    @SerialName("expected_theta") val expectedTheta: List<Double>,
)

@Serializable
private data class CapExample(val features: FeatureVector, val label: Int)

@Serializable
private data class FixtureHyperparameters(val cap: Int)
