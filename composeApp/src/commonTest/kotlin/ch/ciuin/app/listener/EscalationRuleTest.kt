package ch.ciuin.app.listener

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EscalationRuleTest {
    private val rule = EscalationRule(marginThreshold = 0.22)

    @Test
    fun keepsConfidentWindowsOnStageOne() {
        assertFalse(rule.shouldEscalate(floatArrayOf(0.80f, 0.10f, 0.05f, 0.05f)))
    }

    @Test
    fun escalatesWhenTheTopTwoClassesAreClose() {
        assertTrue(rule.shouldEscalate(floatArrayOf(0.40f, 0.30f, 0.20f, 0.10f)))
        // Order of the classes does not matter.
        assertTrue(rule.shouldEscalate(floatArrayOf(0.10f, 0.30f, 0.20f, 0.40f)))
    }

    @Test
    fun aTieAtTheTopEscalates() {
        assertTrue(rule.shouldEscalate(floatArrayOf(0.45f, 0.45f, 0.10f)))
    }

    @Test
    fun theThresholdIsStrict() {
        assertFalse(EscalationRule(0.25).shouldEscalate(floatArrayOf(0.50f, 0.25f, 0.25f)))
        assertTrue(EscalationRule(0.2501).shouldEscalate(floatArrayOf(0.50f, 0.25f, 0.25f)))
    }
}
