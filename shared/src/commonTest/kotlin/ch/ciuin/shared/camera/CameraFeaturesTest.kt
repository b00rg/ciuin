package ch.ciuin.shared.camera

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CameraFeaturesTest {
    @Test
    fun detectsAFrequencyWithTimestampJitterAndADroppedFrame() {
        val samples = (0..720).filter { it != 60 }.map { index ->
            val time = index / 60.0 + 0.0005 * sin(index * 0.9)
            LuminanceSample(
                timestampNanos = (time * 1_000_000_000).toLong(),
                value = 0.6 + 0.15 * sin(2 * PI * 11.5 * time),
            )
        }

        val result = assertNotNull(CameraFeatures.flicker(samples))
        assertEquals(11.5, assertNotNull(result.dominantHz), absoluteTolerance = 0.15)
        assertEquals(60.0, result.sampleRateHz, absoluteTolerance = 0.1)
        assertEquals(27.0, result.maxHz, absoluteTolerance = 0.1)
        assertEquals(0.070, result.flickerIndex, absoluteTolerance = 0.007)
    }

    @Test
    fun aSteadyLightHasARealZeroIndexAndNoFrequency() {
        val samples = (0..90).map { LuminanceSample(it * 1_000_000_000L / 30, 0.5) }

        val result = assertNotNull(CameraFeatures.flicker(samples))
        assertNull(result.dominantHz)
        assertEquals(0.0, result.flickerIndex)
        assertEquals(13.5, result.maxHz, absoluteTolerance = 1e-8)
    }

    @Test
    fun rejectsTooFewUsableSamplesAndFramesWithNoLightSignal() {
        val tooShort = (0..20).map { LuminanceSample(it * 10_000_000L, 0.5) }
        val dark = (0..90).map { LuminanceSample(it * 33_333_333L, 0.0) }
        val repeatedlyInterrupted = (0..99).map {
            LuminanceSample(it / 10 * 4_000_000_000L + it % 10 * 16_666_667L, 0.4)
        }

        assertNull(CameraFeatures.flicker(tooShort))
        assertNull(CameraFeatures.flicker(dark))
        assertNull(CameraFeatures.flicker(repeatedlyInterrupted))
    }
}
