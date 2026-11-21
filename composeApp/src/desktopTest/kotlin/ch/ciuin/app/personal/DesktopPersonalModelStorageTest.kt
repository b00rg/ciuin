package ch.ciuin.app.personal

import ch.ciuin.shared.model.FeatureVector
import ch.ciuin.shared.personal.PersonalPreset
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPersonalModelStorageTest {
    @Test
    fun replaysFeedbackFromDiskAndKeepsResetAcrossRelaunch() = runBlocking<Unit> {
        suspend fun awaitSession(controller: PersonalModelController): PersonalModelSession {
            coroutineContext.job.children.toList().joinAll()
            return assertNotNull(controller.state.session)
        }

        val directory = Files.createTempDirectory("ciuin-personal-model-test-")
        try {
            val file = directory.resolve("ciuin/personal-model.json")
            val storage = DesktopPersonalModelStorage(file)
            assertNull(storage.read())

            val features = FeatureVector(70.0, 80.0, mapOf("siren" to 1.0), 120.0, 0.1, 5)
            val controller = PersonalModelController(storage, this)
            awaitSession(controller)
            controller.selectPreset(PersonalPreset.SOUND_SENSITIVE)
            val preset = awaitSession(controller)

            controller.recordFeedback(features, label = 1)
            val trained = awaitSession(controller)
            assertTrue(trained.score(features) > preset.score(features))

            val relaunched = PersonalModelController(DesktopPersonalModelStorage(file), this)
            val restored = awaitSession(relaunched)
            assertEquals(PersonalPreset.SOUND_SENSITIVE, restored.preset)
            assertEquals(1, restored.exampleCount)
            assertEquals(trained.score(features), restored.score(features))

            relaunched.reset()
            awaitSession(relaunched)
            val afterReset = PersonalModelController(DesktopPersonalModelStorage(file), this)
            val cleared = awaitSession(afterReset)
            assertEquals(preset.preset, cleared.preset)
            assertEquals(0, cleared.exampleCount)
            assertEquals(preset.score(features), cleared.score(features))

            assertEquals(listOf("personal-model.json"), file.parent.toFile().list()?.toList())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
