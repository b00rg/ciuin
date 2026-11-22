package ch.ciuin.app.ui.characters

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * The character timelines from the Motion board, as pure functions of time. Each returns one frame:
 * the handful of numbers the drawing code needs. Distances are in view-box units of the drawing
 * (the board's "7 pt at 176" hop is 3.5 units), so the motion scales with the character.
 *
 * "No loop is faster than a breath": the shortest loop here is the awake sheep's 4 s breath.
 */

/** Numbers straight from the board. Times in seconds. */
internal object MotionSpec {
    // Rest: 4.8 s, cubic in-out. Inhale to 2.4 s (wool 1.025, head sinks 0.35), exhale back.
    const val REST_LOOP = 4.8f
    const val REST_INHALE = 2.4f
    const val REST_WOOL = 0.025f
    const val REST_HEAD_SINK = 0.35f

    // Three z's on the same 4.8 s cycle, a third apart: each rises 22, drifts 9 right, opacity sin(pi t).
    const val Z_COUNT = 3
    const val Z_RISE = 22f
    const val Z_DRIFT = 9f

    // Awake: 8 s loop. Two 4 s breaths (inhale to 1.6 s: wool 1.015, head lifts 0.5),
    // a blink at 4.05 s (eyes to 0.08 high for 0.21 s) and an ear twitch at 6.1 s (-9, +4, 0 degrees in 0.5 s).
    const val AWAKE_LOOP = 8f
    const val AWAKE_BREATH = 4f
    const val AWAKE_INHALE = 1.6f
    const val AWAKE_WOOL = 0.015f
    const val AWAKE_HEAD_LIFT = 0.5f
    const val BLINK_AT = 4.05f
    const val BLINK_LENGTH = 0.21f
    const val BLINK_SCALE = 0.08f
    const val TWITCH_AT = 6.1f
    const val TWITCH_LENGTH = 0.5f
    const val TWITCH_IN = -9f
    const val TWITCH_OUT = 4f

    // Ears up: ears rotate out over 240 ms ease-out; from 120 ms the eyes grow to 1.1 and the brows fade
    // in; the three marks appear 80 ms apart. Then it holds.
    const val ALERT_EARS = 0.24f
    const val ALERT_FACE_AT = 0.12f
    const val ALERT_FACE_LENGTH = 0.12f
    const val ALERT_MARK_GAP = 0.08f
    const val ALERT_MARKS = 3
    const val ALERT_LENGTH = ALERT_FACE_AT + ALERT_MARKS * ALERT_MARK_GAP
    const val ALERT_LOWER_MILLIS = 240

    // Hop: crouch 1.02 x 0.97 from the hooves at 0.55 s, apex 3.5 units up with the shadow at 80% at
    // 0.83 s, land 1.04 x 0.94 at 1.18 s, spring back in 0.42 s. Plays once.
    const val HOP_CROUCH_AT = 0.55f
    const val HOP_APEX_AT = 0.83f
    const val HOP_LAND_AT = 1.18f
    const val HOP_SETTLE = 0.42f
    const val HOP_LENGTH = HOP_LAND_AT + HOP_SETTLE
    const val HOP_HEIGHT = 3.5f
    const val HOP_SHADOW = 0.8f

    /** The tilt of the sheep in the air, as the "You're all set" and "Shared" artboards draw it. */
    const val HOP_TILT = -6f

    // Reading: the board says only "turns a page and the eyes follow". The timing is ours: an 8 s loop
    // with the awake breath, the eyes crossing the pages in 5 s and one page turned in 0.8 s.
    const val READ_LOOP = 8f
    const val READ_SCAN = 5f
    const val READ_TURN_AT = 5.2f
    const val READ_TURN_LENGTH = 0.8f
    const val READ_EYE_TRAVEL = 0.5f

    // Ducks. Waddle in: four steps over 640 ms, the body tilting 7 degrees each way, then two tail
    // wiggles of 160 ms. Pond bob: 2 pt up and down on a 3.6 s sine.
    const val WADDLE_STEPS = 4
    const val WADDLE_STEP = 0.16f
    const val WADDLE_WALK = WADDLE_STEPS * WADDLE_STEP
    const val WADDLE_TILT = 7f
    const val TAIL_WIGGLES = 2
    const val TAIL_WIGGLE = 0.16f
    const val WADDLE_LENGTH = WADDLE_WALK + TAIL_WIGGLES * TAIL_WIGGLE
    const val BOB_LOOP = 3.6f
    const val BOB_DP = 2f

    /** How far the tail swings in a wiggle. The board gives the timing, not the angle. */
    const val TAIL_SWING = 12f
}

/** One z above the resting sheep: travel 0 to 1 along its drift, and its opacity. */
internal class ZFrame(val travel: Float, val alpha: Float)

internal class RestFrame(val woolScale: Float, val headDy: Float, val zs: List<ZFrame>?)

internal class StandFrame(
    val woolScale: Float = 1f,
    val headDy: Float = 0f,
    /** Rotation of each ear about its root, degrees. Positive swings the left ear out. */
    val earLeft: Float = 0f,
    val earRight: Float = 0f,
    /** Vertical scale of the eyes: 1 open, 0.08 in a blink. */
    val eyeOpen: Float = 1f,
    /** 0 awake face, 1 alert face: wider eyes, brows, nose a unit lower. */
    val alertFace: Float = 0f,
    /** Opacity of the three red marks. */
    val marks: FloatArray = NoMarks,
    /** Whole-body offset upward, units. */
    val lift: Float = 0f,
    val squashX: Float = 1f,
    val squashY: Float = 1f,
    val tilt: Float = 0f,
    val shadowScale: Float = 1f,
)

internal class ReadFrame(
    val woolScale: Float = 1f,
    val headDy: Float = 0f,
    /** Horizontal offset of the pupils, units: negative looks at the left page. */
    val eyeDx: Float = 0f,
    val eyeDy: Float = 0f,
    /** Progress of the page from the right block to the left, or a negative number when no page is turning. */
    val pageTurn: Float = -1f,
)

internal class DuckFrame(
    /** Sideways offset as a fraction of the distance it walks in from; 1 is the start, 0 its place. */
    val enter: Float = 0f,
    val alpha: Float = 1f,
    /** Body tilt about the feet, degrees. */
    val tilt: Float = 0f,
    val tail: Float = 0f,
    /** Vertical offset in dp, positive down. */
    val bobDp: Float = 0f,
)

private val NoMarks = floatArrayOf(0f, 0f, 0f)
internal val AllMarks = floatArrayOf(1f, 1f, 1f)

/** The resting sheep at [time] seconds, or its still pose (exhaled, three z's drawn as on the board) when [still]. */
internal fun restFrame(time: Float, still: Boolean = false): RestFrame {
    if (still) return RestFrame(1f, 0f, zs = null)
    val breath = breath(time, MotionSpec.REST_INHALE, MotionSpec.REST_LOOP)
    val zs = List(MotionSpec.Z_COUNT) { index ->
        val travel = fraction(time / MotionSpec.REST_LOOP + index / MotionSpec.Z_COUNT.toFloat())
        ZFrame(travel, sin(PI.toFloat() * travel))
    }
    return RestFrame(1f + MotionSpec.REST_WOOL * breath, MotionSpec.REST_HEAD_SINK * breath, zs)
}

/**
 * The standing sheep.
 *
 * @param time seconds on the awake loop; breath, blink and twitch come from it.
 * @param alert seconds into the ears-up one-shot (0 = awake, [MotionSpec.ALERT_LENGTH] or more = held).
 * @param hop seconds into the hop, or negative when it is not hopping.
 */
internal fun standFrame(time: Float, alert: Float = 0f, hop: Float = -1f): StandFrame {
    val loop = time % MotionSpec.AWAKE_LOOP
    val ears = MotionSpec.ALERT_EARS
    val earsUp = easeOut((alert / ears).coerceIn(0f, 1f))
    // The alert pose holds still: the idle loop fades out as the ears go up.
    val idle = 1f - (alert / ears).coerceIn(0f, 1f)

    val breath = breath(loop % MotionSpec.AWAKE_BREATH, MotionSpec.AWAKE_INHALE, MotionSpec.AWAKE_BREATH) * idle
    val blink = blink(loop - MotionSpec.BLINK_AT)
    val twitch = twitch(loop - MotionSpec.TWITCH_AT) * idle

    val face = ((alert - MotionSpec.ALERT_FACE_AT) / MotionSpec.ALERT_FACE_LENGTH).coerceIn(0f, 1f)
    val marks = FloatArray(MotionSpec.ALERT_MARKS) { index ->
        val start = MotionSpec.ALERT_FACE_AT + index * MotionSpec.ALERT_MARK_GAP
        ((alert - start) / MotionSpec.ALERT_MARK_GAP).coerceIn(0f, 1f)
    }

    var lift = 0f
    var squashX = 1f
    var squashY = 1f
    var tilt = 0f
    var shadow = 1f
    if (hop >= 0f && hop < MotionSpec.HOP_LENGTH) {
        val crouchAt = MotionSpec.HOP_CROUCH_AT
        val apexAt = MotionSpec.HOP_APEX_AT
        val landAt = MotionSpec.HOP_LAND_AT
        when {
            hop < crouchAt -> {
                val k = easeInOut(hop / crouchAt)
                squashX = lerp(1f, 1.02f, k)
                squashY = lerp(1f, 0.97f, k)
            }
            hop < apexAt -> {
                val u = (hop - crouchAt) / (apexAt - crouchAt)
                val k = easeOut(u)
                lift = MotionSpec.HOP_HEIGHT * k
                squashX = lerp(1.02f, 1f, k)
                squashY = lerp(0.97f, 1f, k)
                tilt = MotionSpec.HOP_TILT * k
                shadow = lerp(1f, MotionSpec.HOP_SHADOW, k)
            }
            hop < landAt -> {
                // Falls for the first four fifths, then the hooves touch and the body squashes.
                val u = (hop - apexAt) / (landAt - apexAt)
                val fall = (u / 0.8f).coerceAtMost(1f)
                val down = fall * fall
                lift = MotionSpec.HOP_HEIGHT * (1f - down)
                tilt = MotionSpec.HOP_TILT * (1f - down)
                shadow = lerp(MotionSpec.HOP_SHADOW, 1f, down)
                val squash = ((u - 0.8f) / 0.2f).coerceIn(0f, 1f)
                squashX = lerp(1f, 1.04f, squash)
                squashY = lerp(1f, 0.94f, squash)
            }
            else -> {
                // A damped spring back to rest with one small overshoot.
                val u = (hop - landAt) / MotionSpec.HOP_SETTLE
                val spring = (1f - u) * (1f - u) * cos(2f * PI.toFloat() * 1.25f * u)
                squashX = 1f + 0.04f * spring
                squashY = 1f - 0.06f * spring
            }
        }
    }

    return StandFrame(
        woolScale = 1f + MotionSpec.AWAKE_WOOL * breath,
        headDy = -MotionSpec.AWAKE_HEAD_LIFT * breath,
        earLeft = SheepStandArt.EAR_LEFT_ALERT_DEGREES * earsUp + twitch,
        earRight = SheepStandArt.EAR_RIGHT_ALERT_DEGREES * earsUp,
        eyeOpen = lerp(blink, 1f, 1f - idle),
        alertFace = face,
        marks = marks,
        lift = lift,
        squashX = squashX,
        squashY = squashY,
        tilt = tilt,
        shadowScale = shadow,
    )
}

/** The awake sheep's rest pose: exhaled, eyes open, ears down. */
internal val AwakeStill = StandFrame()

/** The alert sheep once it holds: ears out, wide eyes, brows, all three marks. */
internal val AlertStill = standFrame(0f, alert = MotionSpec.ALERT_LENGTH)

/** The reading sheep at [time] seconds, or its still pose when [still]. */
internal fun readFrame(time: Float, still: Boolean = false): ReadFrame {
    if (still) return ReadFrame()
    val loop = time % MotionSpec.READ_LOOP
    val breath = breath(loop % MotionSpec.AWAKE_BREATH, MotionSpec.AWAKE_INHALE, MotionSpec.AWAKE_BREATH)
    val travel = MotionSpec.READ_EYE_TRAVEL
    val turn = (loop - MotionSpec.READ_TURN_AT) / MotionSpec.READ_TURN_LENGTH
    val eyeDx: Float
    var eyeDy = 0f
    var page = -1f
    when {
        loop < MotionSpec.READ_SCAN -> eyeDx = lerp(-travel, travel, easeInOut(loop / MotionSpec.READ_SCAN))
        turn < 0f -> eyeDx = travel
        turn < 1f -> {
            // The eyes follow the page over the top of the book and back to the left.
            val k = easeInOut(turn)
            page = k
            eyeDx = lerp(travel, -travel, k)
            eyeDy = -0.3f * sin(PI.toFloat() * k)
        }
        else -> eyeDx = -travel
    }
    return ReadFrame(
        woolScale = 1f + MotionSpec.AWAKE_WOOL * breath,
        headDy = -MotionSpec.AWAKE_HEAD_LIFT * breath,
        eyeDx = eyeDx,
        eyeDy = eyeDy,
        pageTurn = page,
    )
}

/** A duck walking in: [time] seconds since it started. After [MotionSpec.WADDLE_LENGTH] it stands still. */
internal fun waddleFrame(time: Float): DuckFrame {
    if (time >= MotionSpec.WADDLE_LENGTH) return DuckFrame()
    if (time < MotionSpec.WADDLE_WALK) {
        val walked = time / MotionSpec.WADDLE_WALK
        return DuckFrame(
            enter = 1f - walked,
            alpha = (time / MotionSpec.WADDLE_STEP).coerceAtMost(1f),
            // One lean per step, alternating sides: a full sine every two steps.
            tilt = MotionSpec.WADDLE_TILT * sin(PI.toFloat() * time / MotionSpec.WADDLE_STEP),
        )
    }
    val wiggle = (time - MotionSpec.WADDLE_WALK) / MotionSpec.TAIL_WIGGLE
    return DuckFrame(tail = MotionSpec.TAIL_SWING * sin(2f * PI.toFloat() * wiggle))
}

/** A duck on the pond at [time] seconds; [phase] (0 to 1) offsets it so neighbours never move together. */
internal fun bobFrame(time: Float, phase: Float): DuckFrame =
    DuckFrame(bobDp = -MotionSpec.BOB_DP * sin(2f * PI.toFloat() * (time / MotionSpec.BOB_LOOP + phase)))

/** 0 exhaled, 1 at the top of the breath: in over [inhale] seconds, out over the rest of [cycle]. */
internal fun breath(time: Float, inhale: Float, cycle: Float): Float {
    val t = time % cycle
    return if (t < inhale) easeInOut(t / inhale) else 1f - easeInOut((t - inhale) / (cycle - inhale))
}

/** Eye height through a blink that started [since] seconds ago: closes, holds shut, opens, a third each. */
private fun blink(since: Float): Float {
    if (since < 0f || since >= MotionSpec.BLINK_LENGTH) return 1f
    val third = MotionSpec.BLINK_LENGTH / 3f
    return when {
        since < third -> lerp(1f, MotionSpec.BLINK_SCALE, since / third)
        since < 2 * third -> MotionSpec.BLINK_SCALE
        else -> lerp(MotionSpec.BLINK_SCALE, 1f, (since - 2 * third) / third)
    }
}

/** Ear angle through a twitch that started [since] seconds ago: in to -9, out to +4, back to 0. */
private fun twitch(since: Float): Float {
    if (since < 0f || since >= MotionSpec.TWITCH_LENGTH) return 0f
    val u = since / MotionSpec.TWITCH_LENGTH
    return when {
        u < 0.3f -> lerp(0f, MotionSpec.TWITCH_IN, easeInOut(u / 0.3f))
        u < 0.66f -> lerp(MotionSpec.TWITCH_IN, MotionSpec.TWITCH_OUT, easeInOut((u - 0.3f) / 0.36f))
        else -> lerp(MotionSpec.TWITCH_OUT, 0f, easeInOut((u - 0.66f) / 0.34f))
    }
}

/** Cubic in-out, the board's easing for breathing. */
internal fun easeInOut(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
}

/** Cubic out. */
internal fun easeOut(t: Float): Float {
    val x = 1f - t.coerceIn(0f, 1f)
    return 1f - x * x * x
}

internal fun lerp(from: Float, to: Float, amount: Float): Float = from + (to - from) * amount

private fun fraction(value: Float): Float = value - kotlin.math.floor(value)
