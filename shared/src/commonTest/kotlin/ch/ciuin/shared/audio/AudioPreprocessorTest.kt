package ch.ciuin.shared.audio

import ch.ciuin.shared.model.SoundClass
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AudioPreprocessorTest {
    @Test
    fun scalesSignedPcmAndAveragesInterleavedChannels() {
        val stereo = shortArrayOf(-32_768, 16_384, 32_767, -32_768)
        assertContentEquals(
            floatArrayOf(-0.25f, -1f / 65_536),
            AudioPreprocessor.pcm16(stereo, 16_000, channels = 2),
        )
        assertContentEquals(
            floatArrayOf(-1f, 0.25f, 0.9999999f),
            AudioPreprocessor.pcm24(intArrayOf(-8_388_608, 2_097_152, 8_388_607), 16_000),
        )
        assertContentEquals(
            floatArrayOf(-1f, 0.25f, 1f),
            AudioPreprocessor.pcm32(intArrayOf(Int.MIN_VALUE, 536_870_912, Int.MAX_VALUE), 16_000),
        )
    }

    @Test
    fun convertsFloatToMonoBeforeClampingAndPreservesRecordedLevel() {
        val stereo = floatArrayOf(1.5f, -0.5f, -2f, -0.25f, 3f, 0f, 0.125f, 0.125f)
        assertContentEquals(
            floatArrayOf(0.5f, -1f, 1f, 0.125f),
            AudioPreprocessor.pcmFloat(stereo, 16_000, channels = 2),
        )
    }

    @Test
    fun rejectsInvalidPcmFrameLayout() {
        assertFailsWith<IllegalArgumentException> { AudioPreprocessor.pcm16(shortArrayOf(0), 16_000, channels = 2) }
        assertFailsWith<IllegalArgumentException> { AudioPreprocessor.pcmFloat(floatArrayOf(), 16_000, channels = 0) }
        assertFailsWith<IllegalArgumentException> { AudioPreprocessor.pcm24(intArrayOf(0), 0) }
    }

    @Test
    fun resamplingPreservesTonePitchPhaseAndLevel() {
        for (sampleRate in listOf(8_000, 22_050, 24_000, 44_100, 44_101, 48_000, 96_000)) {
            val waveform = AudioPreprocessor.pcmFloat(tone(1_000, sampleRate), sampleRate)
            assertEquals(16_000, waveform.size, "output length from $sampleRate Hz")
            val expected = tone(1_000, 16_000)
            assertTrue(
                (32 until waveform.size - 32).all { abs(waveform[it] - expected[it]) < 0.002f },
                "1 kHz tone changed pitch, phase or level when resampled from $sampleRate Hz",
            )
        }
        assertEquals(3, AudioPreprocessor.pcmFloat(FloatArray(7), 48_000).size)
        assertContentEquals(floatArrayOf(), AudioPreprocessor.pcmFloat(floatArrayOf(), 48_000))
    }

    @Test
    fun resamplingAttenuatesFrequenciesAboveTheOutputNyquist() {
        val waveform = AudioPreprocessor.pcmFloat(tone(12_000, 48_000), 48_000)
        val meanSquare = waveform.sumOf { it.toDouble() * it } / waveform.size
        assertTrue(meanSquare < 0.0000125, "A 12 kHz tone should be attenuated by at least 40 dB")
    }

    @Test
    fun clampsFloatSamplesAfterResampling() {
        assertEquals(0.667f, AudioPreprocessor.pcmFloat(floatArrayOf(2f), 48_000).single(), 0.001f)
        assertEquals(1f, AudioPreprocessor.pcmFloat(floatArrayOf(6f), 48_000).single())
    }

    @Test
    fun shortAndEmptyClipsAreZeroPadded() {
        val short = AudioPreprocessor.activeWindows(floatArrayOf(0.25f, -0.125f)).single()
        assertEquals(0, short.start)
        val padded = FloatArray(15_600)
        padded[0] = 0.25f
        padded[1] = -0.125f
        assertContentEquals(padded, short.samples)

        val empty = AudioPreprocessor.activeWindows(floatArrayOf()).single()
        assertEquals(0, empty.start)
        assertContentEquals(FloatArray(15_600), empty.samples)
    }

    @Test
    fun alignsOneWindowToTheTailAndDoesNotDuplicateAnAlignedTail() {
        val samples = FloatArray(31_601) { 0.25f }.apply { this[lastIndex] = -0.5f }
        val windows = AudioPreprocessor.activeWindows(samples)
        assertEquals(listOf(0, 8_000, 16_000, 16_001), windows.map { it.start })
        assertEquals(15_600, windows.last().samples.size)
        assertEquals(-0.5f, windows.last().samples.last())

        val aligned = AudioPreprocessor.activeWindows(samples.copyOf(31_600))
        assertEquals(listOf(0, 8_000, 16_000), aligned.map { it.start })
    }

    @Test
    fun silenceGateKeepsActiveWindowsOrTheFirstLoudestWindow() {
        val active = FloatArray(47_600) { 0.0001f }.apply {
            fill(0.001f, toIndex = 15_600)
            fill(0.2f, fromIndex = 40_000)
        }
        assertEquals(listOf(0, 32_000), AudioPreprocessor.activeWindows(active).map { it.start })

        val quiet = FloatArray(31_600) { if (it < 8_000) 0.0004f else if (it >= 23_600) 0.0009f else 0f }
        assertEquals(16_000, AudioPreprocessor.activeWindows(quiet).single().start)
        assertEquals(0, AudioPreprocessor.activeWindows(FloatArray(31_600)).single().start)
    }

    @Test
    fun clipProbabilitiesAverageTheModelOutputsFromActiveWindows() {
        val samples = FloatArray(39_600).apply {
            fill(0.5f, toIndex = 8_000)
            fill(-0.25f, fromIndex = 31_600)
        }
        val classifier = object : AudioClassifier {
            override fun classify(window: FloatArray): Map<SoundClass, Float> {
                val speech = if (window.first() > 0) 0.75f else 0.125f
                return SoundClass.entries.associateWith {
                    when (it) {
                        SoundClass.SPEECH_CROWD -> speech
                        SoundClass.MUSIC -> 1f - speech
                        else -> 0f
                    }
                }
            }
        }

        val probabilities = classifier.classifyClip(samples)
        assertEquals(9, probabilities.size)
        assertEquals(0.4375f, probabilities[SoundClass.SPEECH_CROWD])
        assertEquals(0.5625f, probabilities[SoundClass.MUSIC])
        assertEquals(1f, probabilities.values.sum())
    }

    private fun tone(frequency: Int, sampleRate: Int): FloatArray = FloatArray(sampleRate) {
        (0.5 * sin(2 * PI * frequency * it / sampleRate)).toFloat()
    }
}
