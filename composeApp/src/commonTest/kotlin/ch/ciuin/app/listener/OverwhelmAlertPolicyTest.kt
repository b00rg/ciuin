package ch.ciuin.app.listener

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverwhelmAlertPolicyTest {
    @Test
    fun onlyAlertsOnAnUpwardCrossingOfTheThreshold() {
        val policy = OverwhelmAlertPolicy(threshold = 0.5)

        assertFalse(policy.onScore(0.4, 0))
        assertFalse(policy.onScore(0.5, 1_000))
        assertTrue(policy.onScore(0.51, 2_000))
        assertFalse(policy.onScore(0.8, 123_000))
        assertFalse(policy.onScore(0.5, 124_000))
        assertTrue(policy.onScore(0.6, 125_000))
    }

    @Test
    fun allowsAtMostOneAlertInTwoMinutes() {
        val policy = OverwhelmAlertPolicy(threshold = 0.5)

        assertTrue(policy.onScore(0.8, 0))
        assertFalse(policy.onScore(0.4, 118_000))
        assertFalse(policy.onScore(0.8, 119_000))
        assertFalse(policy.onScore(0.4, 119_999))
        assertTrue(policy.onScore(0.8, 120_000))
    }

    @Test
    fun snoozeSuppressesCrossingsForTenMinutesWithoutQueuingAnAlert() {
        val policy = OverwhelmAlertPolicy(threshold = 0.5)

        assertTrue(policy.onScore(0.8, 0))
        policy.snooze(5_000)
        assertFalse(policy.onScore(0.4, 604_000))
        assertFalse(policy.onScore(0.8, 604_999))
        assertFalse(policy.onScore(0.8, 605_000))
        assertFalse(policy.onScore(0.4, 606_000))
        assertTrue(policy.onScore(0.8, 607_000))
    }

    @Test
    fun restartingTheListenerKeepsActiveCooldownAndSnooze() {
        val policy = OverwhelmAlertPolicy(threshold = 0.5)

        assertTrue(policy.onScore(0.8, 0))
        policy.resetCrossing()
        assertFalse(policy.onScore(0.8, 1_000))
        policy.resetCrossing()
        assertTrue(policy.onScore(0.8, 120_000))

        policy.snooze(121_000)
        policy.resetCrossing()
        assertFalse(policy.onScore(0.8, 720_999))
        policy.resetCrossing()
        assertTrue(policy.onScore(0.8, 721_000))
    }

    @Test
    fun restoringASnoozeKeepsItsOriginalDeadline() {
        val policy = OverwhelmAlertPolicy(threshold = 0.5)
        policy.snooze(1_000)

        val restored = OverwhelmAlertPolicy(threshold = 0.5, initialSuppressedUntilMillis = policy.suppressedUntilMillis)
        assertFalse(restored.onScore(0.8, 600_999))
        assertFalse(restored.onScore(0.4, 600_999))
        assertTrue(restored.onScore(0.8, 601_000))
    }
}
