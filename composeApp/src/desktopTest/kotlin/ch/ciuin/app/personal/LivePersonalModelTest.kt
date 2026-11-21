package ch.ciuin.app.personal

import ch.ciuin.shared.model.FeatureVector
import ch.ciuin.shared.model.TrainingExample
import ch.ciuin.shared.personal.PersonalPreset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LivePersonalModelTest {
    @Test
    fun scoresAndSavesTheLatestWindowBeforeFallingBackToThePlace() = runBlocking<Unit> {
        var saved: String? = null
        val storage = object : PersonalModelStorage {
            override fun read(): String? = saved
            override fun write(json: String) {
                saved = json
            }
        }
        val controller = PersonalModelController(storage, this)
        suspend fun awaitSession(): PersonalModelSession {
            coroutineContext.job.children.toList().joinAll()
            return assertNotNull(controller.state.session)
        }

        val initial = awaitSession()
        val earlier = FeatureVector(41.0, 49.0, mapOf("other" to 1.0), 150.0, 0.0, 0)
        val latest = FeatureVector(72.0, 85.0, mapOf("siren" to 1.0), 135.0, 0.0, 0)
        val place = FeatureVector(56.0, 61.0, mapOf("speech_crowd" to 1.0), 720.0, 0.05, 25)
        assertNull(controller.liveScore)
        controller.onLiveWindow(earlier)
        assertEquals(initial.score(earlier), controller.liveScore)
        controller.onLiveWindow(latest)
        assertEquals(initial.score(latest), controller.liveScore)
        assertNull(saved)

        controller.recordFeedback(place, label = 1)
        val trained = awaitSession()
        assertEquals(trained.score(latest), controller.liveScore)
        assertNotEquals(initial.score(latest), controller.liveScore)
        controller.selectPreset(PersonalPreset.LIGHT_SENSITIVE)
        val updated = awaitSession()
        assertEquals(updated.score(latest), controller.liveScore)
        assertNotEquals(trained.score(latest), controller.liveScore)

        controller.onLiveWindow(null)
        assertNull(controller.liveScore)
        controller.recordFeedback(place, label = 0)
        assertEquals(2, awaitSession().exampleCount)
        controller.recordFeedback(placeFeatures = null, label = 0)
        assertEquals(2, awaitSession().exampleCount)

        val examples = savedExamples(saved)
        assertEquals(listOf(latest to 1, place to 0), examples.map { it.features to it.label })
    }

    @Test
    fun queuesAlertFeedbackWithItsOwnWindowBehindAnInFlightSave() = runBlocking<Unit> {
        var saved: String? = null
        val firstWriteStarted = CompletableDeferred<Unit>()
        val finishFirstWrite = CountDownLatch(1)
        val storage = object : PersonalModelStorage {
            override fun read(): String? = saved
            override fun write(json: String) {
                if (firstWriteStarted.complete(Unit)) {
                    check(finishFirstWrite.await(5, TimeUnit.SECONDS))
                }
                saved = json
            }
        }
        val controller = PersonalModelController(storage, this)
        coroutineContext.job.children.toList().joinAll()
        val triggering = assertNotNull(controller.onLiveWindow(FeatureVector(72.0, 85.0, mapOf("siren" to 1.0), 0.0, 0.0, 0)))
        val later = assertNotNull(controller.onLiveWindow(FeatureVector(41.0, 49.0, mapOf("other" to 1.0), 0.0, 0.0, 0)))
        controller.recordFeedback(placeFeatures = null, label = 1)
        val feedback = try {
            withTimeout(5_000) { firstWriteStarted.await() }
            val queued = controller.recordAlertFeedback(triggering, label = 0)
            val serviceWaiter = launch(start = CoroutineStart.UNDISPATCHED) { queued.await() }
            serviceWaiter.cancelAndJoin()
            assertFalse(queued.isCompleted)
            controller.onLiveWindow(null)
            queued
        } finally {
            finishFirstWrite.countDown()
        }

        assertTrue(feedback.await())
        assertEquals(
            listOf(TrainingExample(later.features, 1, later.timestamp), TrainingExample(triggering.features, 0, triggering.timestamp)),
            savedExamples(saved),
        )
        assertNull(controller.liveScore)
        assertEquals(2, assertNotNull(controller.state.session).exampleCount)
    }

    @Test
    fun letsTheSameAlertFeedbackBeRetriedAfterAWriteFailure() = runBlocking<Unit> {
        var saved: String? = null
        var attempts = 0
        val storage = object : PersonalModelStorage {
            override fun read(): String? = saved
            override fun write(json: String) {
                if (++attempts == 1) throw IOException("Test write failed")
                saved = json
            }
        }
        val controller = PersonalModelController(storage, this)
        coroutineContext.job.children.toList().joinAll()
        val features = FeatureVector(72.0, 85.0, mapOf("siren" to 1.0), 0.0, 0.0, 0)
        val triggering = LiveWindow(features, timestamp = "2026-11-21T09:00:00Z", score = 0.8)

        assertFalse(controller.recordAlertFeedback(triggering, label = 1).await())
        assertEquals(0, assertNotNull(controller.state.session).exampleCount)
        assertNull(saved)
        assertTrue(controller.recordAlertFeedback(triggering, label = 1).await())
        assertEquals(listOf(TrainingExample(features, 1, "2026-11-21T09:00:00Z")), savedExamples(saved))
    }

    private fun savedExamples(saved: String?): List<TrainingExample> {
        val snapshot = Json.parseToJsonElement(assertNotNull(saved)).jsonObject
        return Json.decodeFromJsonElement(snapshot.getValue("examples"))
    }
}
