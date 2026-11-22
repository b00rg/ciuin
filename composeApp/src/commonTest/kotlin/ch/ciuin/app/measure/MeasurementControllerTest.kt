package ch.ciuin.app.measure

import ch.ciuin.app.core.FakeLocationSource
import ch.ciuin.app.core.FakeMeasurementCapture
import ch.ciuin.app.core.MemorySettingsStorage
import ch.ciuin.app.core.SettingsController
import ch.ciuin.app.route.RoutePoint
import ch.ciuin.shared.api.FakePlacesApi
import ch.ciuin.shared.api.FallbackPlacesApi
import ch.ciuin.shared.api.PlacesApi
import ch.ciuin.shared.model.Measurement
import ch.ciuin.shared.model.MeasurementResult
import ch.ciuin.shared.model.Place
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MeasurementControllerTest {
    private val library = Place("p1", "Library", 46.52, 6.63, "library")
    private val settings = SettingsController(MemorySettingsStorage())
    private val position = FakeLocationSource(RoutePoint(46.52123, 6.63239))
    private val capture = FakeMeasurementCapture()

    @Test
    fun capturesLiveReadingsAndSharesAnUnlistedPlaceWithCoarseCoordinates() = runTest {
        val api = RecordingApi()
        val controller = MeasurementController(capture, api, settings, position, backgroundScope)
        controller.prepare()
        controller.start()
        assertEquals(MeasurementPhase.Requesting, controller.phase)
        runCurrent()
        val capturing = assertIs<MeasurementPhase.Capturing>(controller.phase)
        assertEquals(30, capturing.remainingSeconds)
        assertEquals(MeasurementReadings(db = 53.0, lux = 145.0, nearbyDevices = 2), capturing.readings)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(29, assertIs<MeasurementPhase.Capturing>(controller.phase).remainingSeconds)
        val review = awaitReview(controller)
        assertEquals("unlisted", review.review.measurement.placeId)
        assertEquals(46.521, review.review.measurement.lat)
        assertEquals(6.632, review.review.measurement.lon)
        controller.upload()
        assertIs<MeasurementPhase.Uploading>(controller.phase)
        runCurrent()
        assertEquals(MeasurementPhase.Done(true, null), controller.phase)
        assertEquals(1, settings.sharedMeasurements)
        assertNotNull(api.sent.single().measurementId)
    }

    @Test
    fun deniesCaptureWhenNoSensorIsAvailable() = runTest {
        val denied = FakeMeasurementCapture(CapturePermissions(false, false), canMeasureLux = false)
        val controller = MeasurementController(denied, RecordingApi(), settings, position, backgroundScope)
        controller.prepare(library)
        controller.start()
        runCurrent()

        assertEquals(MeasurementPhase.PermissionDenied, controller.phase)
        assertEquals(0, settings.sharedMeasurements)
    }

    @Test
    fun backgroundingStopsCaptureAndAllowsASeparateAttemptOnReturn() = runTest {
        val events = mutableListOf<String>()
        val sensors = object : MeasurementCapture by capture {
            override fun audio(): Flow<AudioSample> = flow {
                events += "opened"
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    events += "closed"
                }
            }
        }
        val controller = MeasurementController(sensors, RecordingApi(), settings, position, backgroundScope)
        controller.prepare(library)
        controller.start()
        runCurrent()
        controller.setForeground(false)
        runCurrent()
        assertEquals(MeasurementPhase.Ready, controller.phase)
        assertEquals(listOf("opened", "closed"), events)
        controller.start()
        assertEquals(MeasurementPhase.Ready, controller.phase)
        controller.setForeground(true)
        controller.start()
        runCurrent()
        assertEquals(listOf("opened", "closed", "opened"), events)
        controller.discard()
    }

    @Test
    fun retriesAFailedShareWithTheSameMeasurementIdAndReturnsUpdatedPlace() = runTest {
        val api = RecordingApi(library, failing = true)
        val controller = MeasurementController(capture, api, settings, position, backgroundScope)
        controller.prepare(library)
        controller.start()
        awaitReview(controller)
        controller.upload()
        runCurrent()
        val failed = assertIs<MeasurementPhase.Review>(controller.phase)
        assertEquals("Upload failed. Try again.", failed.error)
        assertEquals(0, settings.sharedMeasurements)

        api.failing = false
        controller.upload()
        runCurrent()
        assertEquals(MeasurementPhase.Done(true, library), controller.phase)
        assertEquals(1, settings.sharedMeasurements)
        assertNotNull(api.sent.first().measurementId)
        assertEquals(api.sent.first(), api.sent.last())
    }

    @Test
    fun countsAnOfflineShareOnceAndCanFlushTheQueue() = runTest {
        val api = RecordingApi(library, failing = true)
        val fallback = FallbackPlacesApi(api, FakePlacesApi())
        val controller = MeasurementController(capture, fallback, settings, position, backgroundScope)
        controller.prepare(library)
        controller.start()
        awaitReview(controller)
        controller.upload()
        runCurrent()
        assertEquals(MeasurementPhase.Done(false, null), controller.phase)
        assertEquals(1, controller.pendingUploads)
        assertEquals(1, settings.sharedMeasurements)
        assertFalse(fallback.online.value)

        api.failing = false
        controller.retryNow()
        runCurrent()
        assertTrue(fallback.online.value)
        assertEquals(0, controller.pendingUploads)
        assertEquals(1, settings.sharedMeasurements)
        assertEquals(listOf(api.sent.first(), api.sent.first()), api.sent)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.awaitReview(controller: MeasurementController): MeasurementPhase.Review {
        // The aggregation leaves the UI dispatcher. Wait for that result before the next UI action.
        advanceTimeBy(30_000)
        while (controller.phase is MeasurementPhase.Capturing) kotlinx.coroutines.yield()
        return assertIs<MeasurementPhase.Review>(controller.phase)
    }

    private class RecordingApi(private val updated: Place? = null, var failing: Boolean = false) :
        PlacesApi by FakePlacesApi() {
        val sent = mutableListOf<Measurement>()
        override suspend fun postMeasurement(m: Measurement): MeasurementResult {
            sent += m
            check(!failing) { "Offline" }
            return MeasurementResult(stored = true, place = updated)
        }
    }
}
