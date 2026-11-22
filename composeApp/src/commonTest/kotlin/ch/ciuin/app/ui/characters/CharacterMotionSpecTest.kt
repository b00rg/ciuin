package ch.ciuin.app.ui.characters

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The keyframes on the Motion board, checked against the timelines that draw the characters. */
class CharacterMotionSpecTest {
    @Test
    fun restBreathesOverFourPointEightSeconds() {
        val exhaled = restFrame(0f)
        assertNear(1f, exhaled.woolScale)
        assertNear(0f, exhaled.headDy)

        val inhaled = restFrame(2.4f)
        assertNear(1.025f, inhaled.woolScale)
        assertNear(0.35f, inhaled.headDy)

        assertNear(restFrame(1.3f).woolScale, restFrame(1.3f + 4.8f).woolScale)
    }

    @Test
    fun threeZsDriftAThirdApart() {
        val zs = restFrame(0.8f).zs!!
        assertEquals(3, zs.size)
        val travels = zs.map { it.travel }.sorted()
        assertNear(1f / 3f, travels[1] - travels[0])
        assertNear(1f / 3f, travels[2] - travels[1])
        // Opacity is sin(pi t): nothing at either end of the drift, full in the middle.
        assertNear(0f, restFrame(0f).zs!![0].alpha)
        assertNear(1f, restFrame(2.4f).zs!![0].alpha)
    }

    @Test
    fun restStillPoseDoesNotMove() {
        val still = restFrame(3.1f, still = true)
        assertNear(1f, still.woolScale)
        assertNear(0f, still.headDy)
        assertEquals(null, still.zs)
    }

    @Test
    fun awakeInhalesAtOnePointSix() {
        val inhaled = standFrame(1.6f)
        assertNear(1.015f, inhaled.woolScale)
        assertNear(-0.5f, inhaled.headDy)
        assertNear(1f, standFrame(0f).woolScale)
        assertNear(1f, standFrame(4f).woolScale)
        assertNear(1.015f, standFrame(5.6f).woolScale)
    }

    @Test
    fun awakeBlinksAtFourPointOhFive() {
        assertNear(1f, standFrame(4.04f).eyeOpen)
        assertNear(0.08f, standFrame(4.05f + 0.105f).eyeOpen)
        assertNear(1f, standFrame(4.05f + 0.21f).eyeOpen)
    }

    @Test
    fun awakeTwitchesAnEarAtSixPointOne() {
        assertNear(0f, standFrame(6.09f).earLeft)
        val samples = (0..50).map { standFrame(6.1f + it * 0.01f).earLeft }
        assertNear(-9f, samples.min())
        assertNear(4f, samples.max())
        assertNear(0f, standFrame(6.6f).earLeft)
        assertTrue(samples.indexOf(samples.min()) < samples.indexOf(samples.max()), "in before out")
        // Only one ear twitches.
        assertNear(0f, standFrame(6.25f).earRight)
    }

    @Test
    fun earsGoUpOnceAndHold() {
        val start = standFrame(2f, alert = 0f)
        assertNear(0f, start.alertFace)

        val earsOut = standFrame(2f, alert = 0.24f)
        assertNear(52f, earsOut.earLeft)
        assertNear(-50f, earsOut.earRight)

        // From 120 ms the face changes; the marks follow 80 ms apart.
        assertNear(0f, standFrame(2f, alert = 0.12f).alertFace)
        assertNear(1f, standFrame(2f, alert = 0.24f).alertFace)
        val marksMidway = standFrame(2f, alert = 0.24f).marks
        assertNear(1f, marksMidway[0])
        assertNear(0.5f, marksMidway[1])
        assertNear(0f, marksMidway[2])

        val held = standFrame(2f, alert = MotionSpec.ALERT_LENGTH)
        held.marks.forEach { assertNear(1f, it) }
        // It does not shake, flash or loop: the idle loop is gone, whatever the clock says.
        for (time in listOf(1.6f, 4.12f, 6.25f)) {
            val later = standFrame(time, alert = MotionSpec.ALERT_LENGTH)
            assertNear(1f, later.woolScale)
            assertNear(1f, later.eyeOpen)
            assertNear(52f, later.earLeft)
        }
    }

    @Test
    fun hopFollowsItsKeyframes() {
        val crouch = standFrame(0f, hop = 0.55f)
        assertNear(1.02f, crouch.squashX)
        assertNear(0.97f, crouch.squashY)
        assertNear(0f, crouch.lift)

        val apex = standFrame(0f, hop = 0.83f)
        assertNear(3.5f, apex.lift)
        assertNear(0.8f, apex.shadowScale)

        val land = standFrame(0f, hop = 1.18f)
        assertNear(1.04f, land.squashX)
        assertNear(0.94f, land.squashY)
        assertNear(0f, land.lift)

        val settled = standFrame(0f, hop = 1.18f + 0.42f)
        assertNear(1f, settled.squashX)
        assertNear(1f, settled.squashY)
        assertNear(1f, settled.shadowScale)
    }

    @Test
    fun duckWaddlesInOverFourSteps() {
        assertNear(1f, waddleFrame(0f).enter)
        assertNear(0.5f, waddleFrame(0.32f).enter)
        assertNear(7f, waddleFrame(0.08f).tilt)
        assertNear(-7f, waddleFrame(0.24f).tilt)
        assertNear(7f, waddleFrame(0.40f).tilt)
        assertNear(-7f, waddleFrame(0.56f).tilt)

        // Then two tail wiggles of 160 ms, with the body upright and in place.
        val wiggling = waddleFrame(0.64f + 0.04f)
        assertNear(0f, wiggling.enter)
        assertNear(0f, wiggling.tilt)
        assertTrue(abs(wiggling.tail) > 1f)
        assertNear(wiggling.tail, waddleFrame(0.64f + 0.04f + 0.16f).tail)

        val arrived = waddleFrame(0.96f)
        assertNear(0f, arrived.tail)
        assertNear(1f, arrived.alpha)
    }

    @Test
    fun ducksBobTwoPointsEitherWayAndNeverTogether() {
        val samples = (0 until 360).map { bobFrame(it * 0.01f, phase = 0f).bobDp }
        assertNear(2f, samples.max())
        assertNear(-2f, samples.min())
        assertNear(bobFrame(1f, 0f).bobDp, bobFrame(1f + 3.6f, 0f).bobDp)
        assertTrue(abs(bobFrame(1f, 0f).bobDp - bobFrame(1f, 1f / 3f).bobDp) > 0.5f)
    }

    @Test
    fun noLoopIsFasterThanABreath() {
        for (loop in listOf(MotionSpec.REST_LOOP, MotionSpec.AWAKE_BREATH, MotionSpec.READ_LOOP, MotionSpec.BOB_LOOP)) {
            assertTrue(loop >= 3.6f, "a ${loop}s loop is faster than a breath")
        }
    }

    private fun assertNear(expected: Float, actual: Float, tolerance: Float = 0.01f) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }
}
