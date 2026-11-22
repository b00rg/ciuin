package ch.ciuin.app.listener

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SoundClassSmootherTest {
    private fun probs(siren: Double) = mapOf("siren" to siren, "traffic" to 1 - siren)

    @Test
    fun averagesTheLastThreeWindows() {
        val smoother = SoundClassSmoother(windows = 3)

        assertEquals(0.9, smoother.smooth(probs(0.9))!!.getValue("siren"), 1e-9)
        assertEquals(0.6, smoother.smooth(probs(0.3))!!.getValue("siren"), 1e-9)
        assertEquals(0.5, smoother.smooth(probs(0.3))!!.getValue("siren"), 1e-9)
        // The first window (0.9) drops out of the average.
        assertEquals(0.3, smoother.smooth(probs(0.3))!!.getValue("siren"), 1e-9)
    }

    @Test
    fun keepsProbabilitiesSummingToOne() {
        val smoother = SoundClassSmoother()
        smoother.smooth(probs(0.2))
        val out = smoother.smooth(probs(0.7))!!

        assertEquals(1.0, out.values.sum(), 1e-9)
    }

    @Test
    fun silenceClearsTheHistory() {
        val smoother = SoundClassSmoother()
        smoother.smooth(probs(0.1))
        smoother.smooth(probs(0.1))

        assertEquals(emptyMap(), smoother.smooth(emptyMap()))
        assertNull(smoother.smooth(null))
        assertEquals(0.9, smoother.smooth(probs(0.9))!!.getValue("siren"), 1e-9)
    }

    @Test
    fun oneWindowIsAPassThrough() {
        val smoother = SoundClassSmoother(windows = 1)
        smoother.smooth(probs(0.1))

        assertEquals(0.8, smoother.smooth(probs(0.8))!!.getValue("siren"), 1e-9)
    }
}
