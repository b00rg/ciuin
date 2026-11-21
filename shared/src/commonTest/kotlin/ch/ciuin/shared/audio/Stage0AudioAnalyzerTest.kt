package ch.ciuin.shared.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Stage0AudioAnalyzerTest {
    @Test
    fun agreesWithNumpyForSuccessiveWindows() {
        // Regenerate with python3 ml/tests/stage0_numpy_reference.py.
        val expectedByRate = mapOf(
            16_000 to listOf(
                Stage0AudioMetrics(-27.963014347967, 0.0),
                Stage0AudioMetrics(-27.963014347967, 0.0),
                Stage0AudioMetrics(-10.400721044488, 0.282221096798),
                Stage0AudioMetrics(-7.970111252617, 0.424143999088),
                Stage0AudioMetrics(Double.NEGATIVE_INFINITY, 0.0),
                Stage0AudioMetrics(-27.963014347967, 0.353553388892),
            ),
            48_000 to listOf(
                Stage0AudioMetrics(-27.968145840649, 0.0),
                Stage0AudioMetrics(-27.968145840649, 0.0),
                Stage0AudioMetrics(-10.400843483161, 0.282368907905),
                Stage0AudioMetrics(-17.418565989759, 0.424201116711),
                Stage0AudioMetrics(Double.NEGATIVE_INFINITY, 0.0),
                Stage0AudioMetrics(-27.968145840649, 0.353553389257),
            ),
        )
        for ((sampleRateHz, expected) in expectedByRate) {
            val analyzer = Stage0AudioAnalyzer(sampleRateHz)
            for ((index, window) in toneWindows(sampleRateHz).withIndex()) {
                assertMetrics(expected[index], analyzer.analyze(window), "$sampleRateHz Hz, window $index")
            }
        }
    }

    @Test
    fun countsTheNyquistBinOnce() {
        val sampleRateHz = 8_192
        val analyzer = Stage0AudioAnalyzer(sampleRateHz)
        val nyquist = FloatArray(sampleRateHz) { if (it % 2 == 0) 0.5f else -0.5f }
        analyzer.analyze(FloatArray(sampleRateHz))
        assertMetrics(Stage0AudioMetrics(-5.091064895681, 0.5), analyzer.analyze(nyquist), "Nyquist")
    }

    @Test
    fun handlesDcSilenceDecreasesAndReset() {
        val sampleRateHz = 16_000
        val analyzer = Stage0AudioAnalyzer(sampleRateHz)
        val silence = FloatArray(sampleRateHz)
        val dc = FloatArray(sampleRateHz) { 0.25f }
        val tone = FloatArray(sampleRateHz) { (0.5 * cosine(it.toDouble() / sampleRateHz, 1000)).toFloat() }
        val quieter = FloatArray(sampleRateHz) { tone[it] * 0.5f }

        assertEquals(Stage0AudioMetrics(Double.NEGATIVE_INFINITY, 0.0), analyzer.analyze(dc))
        val onset = analyzer.analyze(tone)
        assertEquals(0.5 / sqrt(2.0), onset.spectralFlux, 1e-8)
        assertEquals(0.0, analyzer.analyze(quieter).spectralFlux, 1e-8)
        assertEquals(Stage0AudioMetrics(Double.NEGATIVE_INFINITY, 0.0), analyzer.analyze(silence))
        analyzer.reset()
        assertEquals(onset.copy(spectralFlux = 0.0), analyzer.analyze(tone))
    }

    @Test
    fun measuresAudioBelowTheClassifierSilenceGate() {
        val sampleRateHz = 16_000
        val analyzer = Stage0AudioAnalyzer(sampleRateHz)
        val tone = FloatArray(sampleRateHz) { (0.0001 * cosine(it.toDouble() / sampleRateHz, 1000)).toFloat() }
        analyzer.analyze(FloatArray(sampleRateHz))
        val metrics = analyzer.analyze(tone)

        assertTrue(metrics.levelDbfsA.isFinite() && metrics.levelDbfsA < -60.0)
        assertEquals(0.0001 / sqrt(2.0), metrics.spectralFlux, 1e-11)
    }

    @Test
    fun rejectsInvalidMonoWindows() {
        val sampleRateHz = 16_000
        val analyzer = Stage0AudioAnalyzer(sampleRateHz)

        assertFailsWith<IllegalArgumentException> { Stage0AudioAnalyzer(0) }
        assertFailsWith<IllegalArgumentException> { analyzer.analyze(FloatArray(sampleRateHz - 1)) }
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, 1.01f, -1.01f)) {
            val window = FloatArray(sampleRateHz).apply { this[lastIndex] = invalid }
            assertFailsWith<IllegalArgumentException> { analyzer.analyze(window) }
        }
    }

    private fun assertMetrics(expected: Stage0AudioMetrics, actual: Stage0AudioMetrics, context: String) {
        if (expected.levelDbfsA.isFinite()) {
            assertEquals(expected.levelDbfsA, actual.levelDbfsA, 1e-6, "$context dBFS(A)")
        } else {
            assertEquals(expected.levelDbfsA, actual.levelDbfsA, "$context dBFS(A)")
        }
        assertEquals(expected.spectralFlux, actual.spectralFlux, 1e-8, "$context spectral flux")
    }

    private fun toneWindows(sampleRateHz: Int): List<FloatArray> {
        val low = FloatArray(sampleRateHz) {
            (0.125 + 0.5 * cosine(it.toDouble() / sampleRateHz, 100)).toFloat()
        }
        val mixed = FloatArray(sampleRateHz) {
            val seconds = it.toDouble() / sampleRateHz
            (0.125 + 0.25 * cosine(seconds, 100) + 0.4 * cosine(seconds, 1234)).toFloat()
        }
        val high = FloatArray(sampleRateHz) {
            (0.6 * cosine(it.toDouble() / sampleRateHz, sampleRateHz * 7 / 16)).toFloat()
        }
        return listOf(low, low, mixed, high, FloatArray(sampleRateHz), low)
    }

    private fun cosine(seconds: Double, frequencyHz: Int): Double = cos(2 * PI * frequencyHz * seconds)
}
