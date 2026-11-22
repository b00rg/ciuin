package ch.ciuin.app.measure

import ch.ciuin.app.ui.measure.soundLine
import ch.ciuin.shared.model.Place
import ch.ciuin.shared.model.SoundClass
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MeasurementSessionTest {
    private val place = Place("osm:1", "Square", 46.5, 6.6, "square")
    private val allowed = CapturePermissions(camera = true, microphone = true, bluetooth = true)

    @Test
    fun capturesForThirtySecondsAndKeepsObservedZeros() = runTest {
        val capture = object : MeasurementCapture {
            override val device = "test"
            override val canMeasureLux = true
            override suspend fun requestPermissions() = allowed
            override fun camera() = flow<CameraSample> { delay(30_001) }
            override val canScanBluetooth = true
            override fun bluetooth() = flow {
                emit(BleSighting(device = 1, rssi = -90)) // far at first...
                emit(BleSighting(device = 2, rssi = -95)) // far: ignored
                emit(BleSighting(device = 1, rssi = -70)) // ...then near: counted once
                emit(BleSighting(device = 1, rssi = -60))
                emit(BleSighting(device = 3, rssi = NEARBY_RSSI_DBM)) // on the cut-off: counted
                delay(30_001)
                emit(BleSighting(device = 4, rssi = -50)) // after the window: ignored
            }
            override fun lux() = flowOf(0.0, 0.0, Double.NaN)
            override fun audio() = flowOf(
                AudioSample(50.0, 53.0, emptyMap(), "s"),
                AudioSample(60.0, 62.0, emptyMap(), "s"),
            )
        }

        val review = measurePlace(place, capture, allowed)

        assertEquals(30_000L, currentTime)
        assertTrue(review.canUpload)
        assertEquals(30, review.measurement.durationS)
        assertNotNull(review.measurement.measurementId)
        val features = review.measurement.features
        assertEquals(57.4036, assertNotNull(features.dbMean), absoluteTolerance = 0.001)
        assertEquals(62.0, features.dbPeak)
        assertEquals(SoundClass.entries.associate { it.label to 0.0 }, features.soundClasses)
        assertEquals(0.0, features.luxMean)
        assertEquals(2, features.nearbyDevices)
        assertNull(features.peopleCount)
        assertNull(features.flickerIndex)
    }

    @Test
    fun keepsDbfsLocalAndSkipsDeniedOrUnsupportedDevices() = runTest {
        val permissions = CapturePermissions(camera = false, microphone = true)
        val capture = object : MeasurementCapture {
            override val device = "test"
            override val canMeasureLux = false
            override suspend fun requestPermissions() = permissions
            override fun camera() = error("Camera permission was denied")
            override fun lux() = error("There is no light sensor")
            override fun audio() = flowOf(AudioSample(-40.0, -37.0, null, null, dbfs = true))
        }

        val review = measurePlace(place, capture, permissions)

        assertFalse(review.canUpload)
        assertEquals(AudioLevels(-40.0, -37.0), review.dbfs)
        assertNull(review.measurement.features.dbMean)
        assertNull(review.measurement.features.soundClasses)
        assertNull(review.measurement.features.luxMean)
        assertNull(review.measurement.features.nearbyDevices) // no Bluetooth permission: unavailable, not zero
    }

    @Test
    fun sharesSoundClassesWhileKeepingUncalibratedLevelOnThePhone() = runTest {
        val permissions = CapturePermissions(camera = false, microphone = true)
        val classes = SoundClass.entries.associate { it.label to if (it == SoundClass.TRAFFIC) 1.0 else 0.0 }
        val capture = object : MeasurementCapture {
            override val device = "test"
            override val canMeasureLux = false
            override suspend fun requestPermissions() = permissions
            override fun camera() = emptyFlow<CameraSample>()
            override fun lux() = emptyFlow<Double>()
            override fun audio() = flowOf(AudioSample(-40.0, -37.0, classes, "Student S", dbfs = true))
        }

        val review = measurePlace(place, capture, permissions)

        assertTrue(review.canUpload)
        assertEquals("Student S", review.measurement.modelVariant)
        assertEquals(classes, review.measurement.features.soundClasses)
        assertNull(review.measurement.features.dbMean)
        assertNull(review.measurement.features.dbPeak)
        assertEquals(AudioLevels(-40.0, -37.0), review.dbfs)
        assertEquals("Mostly traffic · Student S · Uncalibrated level stays here", soundLine(review).subtitle)
    }

    @Test
    fun cancellationClosesAnOpenCameraAndProducesNoReview() = runTest {
        var opened = false
        var closed = false
        var reviewed = false
        val capture = object : MeasurementCapture {
            override val device = "test"
            override val canMeasureLux = false
            override suspend fun requestPermissions() = allowed
            override fun camera() = flow<CameraSample> {
                opened = true
                try {
                    awaitCancellation()
                } finally {
                    closed = true
                }
            }
            override fun lux() = emptyFlow<Double>()
            override fun audio() = emptyFlow<AudioSample>()
        }

        val session = launch {
            measurePlace(place, capture, allowed)
            reviewed = true
        }
        advanceTimeBy(1_000L)
        assertTrue(opened)
        session.cancel()
        session.join()

        assertTrue(closed)
        assertFalse(reviewed)
    }
}
