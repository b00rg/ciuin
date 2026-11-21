package ch.ciuin.app.personal

import ch.ciuin.shared.model.FeatureVector
import ch.ciuin.shared.model.TrainingExample
import ch.ciuin.shared.personal.LogisticPersonalModel
import ch.ciuin.shared.personal.PersonalPreset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonalModelSessionTest {
    private val quiet = FeatureVector(35.0, 35.0, emptyMap(), 0.0, 0.0, 0)
    private val bright = FeatureVector(62.0, 75.0, mapOf("music" to 1.0), 4_000.0, 0.6, 5)

    @Test
    fun firstLaunchUsesTheDefaultPreset() {
        val session = PersonalModelSession.restore(null)

        assertEquals(PersonalPreset.AUTISM_FRIENDLY, session.preset)
        assertEquals(0, session.exampleCount)
        assertEquals(0.0024726231566347743, session.score(quiet), absoluteTolerance = 1e-9)
    }

    @Test
    fun relaunchRestoresTheSelectedPresetAndTheLast200Examples() {
        val examples = List(205) { index ->
            val label = if (index < 5 || index % 5 < 2) 1 else 0
            val features = if (index >= 5 && label == 1) bright else quiet
            example(features, label, index)
        }
        val session = examples.fold(PersonalModelSession.restore(null)) { current, example ->
            current.withFeedback(example)
        }.withPreset(PersonalPreset.LIGHT_SENSITIVE)
        val encoded = session.encode()
        val restored = PersonalModelSession.restore(encoded)

        val retained = examples.takeLast(200)
        val storedExamples = Json.parseToJsonElement(encoded).jsonObject.getValue("examples")
        assertEquals(retained, Json.decodeFromJsonElement<List<TrainingExample>>(storedExamples))
        assertEquals(PersonalPreset.LIGHT_SENSITIVE, restored.preset)
        assertEquals(200, restored.exampleCount)

        val expected = LogisticPersonalModel(PersonalPreset.LIGHT_SENSITIVE)
        retained.forEach(expected::update)
        for (probe in listOf(quiet, bright)) {
            assertEquals(expected.score(probe), session.score(probe), absoluteTolerance = 1e-9)
            assertEquals(expected.score(probe), restored.score(probe), absoluteTolerance = 1e-9)
        }
    }

    @Test
    fun clearedFeedbackStaysClearedAfterRelaunch() {
        val crowd = quiet.copy(peopleCount = 30)
        val presetScore = 0.07585818002124355
        val trained = PersonalModelSession.restore(null).withPreset(PersonalPreset.CROWD_SENSITIVE)
            .withFeedback(example(crowd, label = 1, index = 0))
        assertTrue(trained.score(crowd) > presetScore)

        val restored = PersonalModelSession.restore(trained.reset().encode())

        assertEquals(PersonalPreset.CROWD_SENSITIVE, restored.preset)
        assertEquals(0, restored.exampleCount)
        assertEquals(presetScore, restored.score(crowd), absoluteTolerance = 1e-9)
    }

    private fun example(features: FeatureVector, label: Int, index: Int): TrainingExample =
        TrainingExample(features, label, "2026-11-21T12:00:00.${index.toString().padStart(3, '0')}Z")
}
