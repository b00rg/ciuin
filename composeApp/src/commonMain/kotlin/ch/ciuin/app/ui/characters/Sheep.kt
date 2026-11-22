package ch.ciuin.app.ui.characters

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.ciuin.app.ui.theme.CiuinMotion
import ch.ciuin.app.ui.theme.CiuinType
import ch.ciuin.app.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch

/**
 * What the sheep is doing. The sheep is you: it reports how a place feels.
 *
 * @property description what a screen reader says for the picture.
 */
enum class SheepPose(val description: String) {
    /**
     * Asleep on its feet, eyes shut. Shown when the area is calm for you: the map sheet and the You
     * tab. Breathes on a 4.8 s loop while three z's drift up.
     */
    Rest("Sheep resting"),

    /**
     * Standing, eyes open. The default: place sheets, onboarding, moderate areas. On an 8 s loop it
     * breathes twice, blinks once and twitches an ear once.
     */
    Awake("Sheep awake"),

    /**
     * Ears out, wide eyes, three red marks. Shown above your limit: the alert screen and the
     * notification. The ears lift once, with one soft haptic, and then it holds still; it never shakes,
     * flashes or loops.
     */
    Alert("Sheep with its ears up"),

    /**
     * Sitting with a book. Shown while learning or measuring: the measure ring, empty layers, the
     * how-to card. Turns a page now and then and the eyes follow.
     */
    Reading("Sheep reading"),

    /**
     * One hop, then [Awake]. The end of onboarding and after a shared measurement. It crouches, hops
     * 7 pt (at 176 pt tall) and lands with a small spring, 1.6 s in all.
     */
    Hop("Sheep hopping"),
}

/**
 * The sheep, drawn as vectors at any size and animated as the Motion board specifies.
 *
 * Give it a size with [modifier]; the drawing is fitted inside and centred. The poses have slightly
 * different proportions (Rest is 89:81, the standing poses and Reading are square), so use the sizes
 * from the artboard you are building. Without a size it is 88 dp.
 *
 * Changing [pose] cross-fades in 400 ms, except between the standing poses, which share a body:
 * there the ears simply go up or come back down.
 *
 * When [LocalReduceMotion] is on, nothing loops: each pose shows its rest frame (the resting sheep
 * keeps three still z's) and a change of pose is a 150 ms fade.
 *
 * The z's of [SheepPose.Rest] drift up and to the right, outside the sheep's own bounds by about a
 * third of its width; nothing clips them. Pass `zzz = false` where there is no room.
 *
 * @param contentDescription what a screen reader says; null when the text beside the sheep already
 * says how the place feels.
 * @param haptic whether [SheepPose.Alert] gives its one soft haptic when the ears go up.
 * @param headphones whether the standing sheep wears headphones (the Sound layer). Drawn with the head, so they move with it.
 * @param microphone whether the reading sheep holds a microphone instead of its book (the Measure tab).
 * @param onHopEnd called when a [SheepPose.Hop] has landed and settled.
 */
@Composable
fun Sheep(
    pose: SheepPose,
    modifier: Modifier = Modifier,
    contentDescription: String? = pose.description,
    zzz: Boolean = true,
    haptic: Boolean = true,
    headphones: Boolean = false,
    microphone: Boolean = false,
    onHopEnd: () -> Unit = {},
) {
    val reduceMotion = LocalReduceMotion.current
    val feedback = LocalHapticFeedback.current
    val hopEnded by rememberUpdatedState(onHopEnd)

    // `shown` fades in over `leaving`; `alert` and `hop` are positions on their one-shot timelines.
    var shown by remember { mutableStateOf(pose) }
    var leaving by remember { mutableStateOf<SheepPose?>(null) }
    val fade = remember { Animatable(1f) }
    val alert = remember { Animatable(0f) }
    val hop = remember { Animatable(HOP_IDLE) }
    var hopped by remember { mutableStateOf(false) }
    val earsUp by remember { derivedStateOf { alert.value >= MotionSpec.ALERT_EARS } }
    val clock = rememberCharacterClock(
        running = !reduceMotion && (pose != SheepPose.Alert || !earsUp || leaving != null),
    )

    LaunchedEffect(pose, reduceMotion) {
        val previous = shown
        shown = pose
        if (pose != SheepPose.Hop) hopped = false
        if (pose == SheepPose.Alert && haptic && (previous != pose || alert.value == 0f)) {
            feedback.performHapticFeedback(HapticFeedbackType.VirtualKey)
        }

        // Poses that share a body change in place; anything else, and everything under Reduce
        // motion, cross-fades. A fade that a quick second change interrupted is carried to its end.
        val crossFade = previous != pose && (reduceMotion || previous.body != pose.body)
        launch {
            if (crossFade) {
                leaving = previous
                fade.snapTo(0f)
            }
            if (leaving != null) {
                val spec = if (reduceMotion) {
                    CiuinMotion.reducedFade()
                } else {
                    tween<Float>(CiuinMotion.SHEEP_STATE_MILLIS, easing = CiuinMotion.Ease)
                }
                fade.animateTo(1f, spec)
                leaving = null
            }
        }

        if (reduceMotion) {
            alert.snapTo(if (pose == SheepPose.Alert) MotionSpec.ALERT_LENGTH else 0f)
            hop.snapTo(HOP_IDLE)
            if (pose == SheepPose.Hop && !hopped) {
                hopped = true
                hopEnded()
            }
            return@LaunchedEffect
        }

        launch {
            if (pose == SheepPose.Alert) {
                val left = MotionSpec.ALERT_LENGTH - alert.value
                alert.animateTo(MotionSpec.ALERT_LENGTH, tween((left * 1000).toInt(), easing = LinearEasing))
            } else if (alert.value > 0f) {
                // The ears lower when you leave or dismiss.
                alert.animateTo(0f, tween(MotionSpec.ALERT_LOWER_MILLIS, easing = LinearEasing))
            }
        }
        if (pose == SheepPose.Hop && !hopped) {
            hop.snapTo(0f)
            hop.animateTo(MotionSpec.HOP_LENGTH, tween((MotionSpec.HOP_LENGTH * 1000).toInt(), easing = LinearEasing))
            hop.snapTo(HOP_IDLE)
            hopped = true
            hopEnded()
        } else {
            hop.snapTo(HOP_IDLE)
        }
    }

    val z = rememberZ(enabled = zzz)
    val semantics = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else {
        Modifier
    }
    Canvas(modifier.defaultMinSize(88.dp, 88.dp).then(semantics)) {
        val time = clock.floatValue
        val old = leaving
        if (old != null) fading(1f - fade.value) { drawPose(old, time, reduceMotion, alert.value, hop.value, z, headphones, microphone) }
        fading(if (old != null) fade.value else 1f) { drawPose(shown, time, reduceMotion, alert.value, hop.value, z, headphones, microphone) }
    }
}

/** Poses with the same body change in place; the others cross-fade. */
private val SheepPose.body: Int
    get() = when (this) {
        SheepPose.Rest -> 0
        SheepPose.Awake, SheepPose.Alert, SheepPose.Hop -> 1
        SheepPose.Reading -> 2
    }

private const val HOP_IDLE = -1f

private fun DrawScope.drawPose(
    pose: SheepPose,
    time: Float,
    still: Boolean,
    alert: Float,
    hop: Float,
    z: TextLayoutResult?,
    headphones: Boolean,
    microphone: Boolean,
) {
    when (pose) {
        SheepPose.Rest -> inViewBox(SheepRestArt.WIDTH, SheepRestArt.HEIGHT) {
            drawSheepRest(restFrame(time, still), z)
        }
        SheepPose.Awake, SheepPose.Alert, SheepPose.Hop -> inViewBox(SheepStandArt.WIDTH, SheepStandArt.HEIGHT) {
            val frame = when {
                !still -> standFrame(time, alert, hop)
                pose == SheepPose.Alert -> AlertStill
                else -> AwakeStill
            }
            drawSheepStand(frame, headphones)
        }
        SheepPose.Reading -> inViewBox(SheepReadArt.WIDTH, SheepReadArt.HEIGHT) {
            drawSheepRead(readFrame(time, still), microphone)
        }
    }
}

/** The letter z in Poppins Bold, laid out once; the drawing scales it. */
@Composable
private fun rememberZ(enabled: Boolean): TextLayoutResult? {
    if (!enabled) return null
    val measurer = rememberTextMeasurer(cacheSize = 1)
    val density = LocalDensity.current
    return remember(measurer, density) { measurer.measure("z", CiuinType.Zzz.copy(fontSize = 40.sp), density = density) }
}

/**
 * Seconds since this character appeared, advancing every frame while [running]. It wraps every 72 s,
 * a whole number of every loop (3.6, 4.8 and 8 s), so the loops never jump and the number stays small.
 */
@Composable
internal fun rememberCharacterClock(running: Boolean): FloatState {
    val seconds = remember { mutableFloatStateOf(0f) }
    if (running) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) {
                withFrameNanos { now -> seconds.floatValue = ((now - start) % CLOCK_WRAP_NANOS) / 1_000_000_000f }
            }
        }
    }
    return seconds
}

private const val CLOCK_WRAP_NANOS = 72_000_000_000L
