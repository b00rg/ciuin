package ch.ciuin.app.ui.characters

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import ch.ciuin.app.ui.theme.CiuinMotion
import ch.ciuin.app.ui.theme.LocalReduceMotion
import kotlin.math.min

/** How a duck moves. */
enum class DuckMotion {
    /** Standing still: in cards and on the pasture. */
    Still,

    /**
     * Floating on the pond of the You tab: drifts 2 pt up and down on a 3.6 s sine. Give neighbours
     * phases a third apart so they never move together.
     */
    Bob,

    /**
     * Walking in from the right after "Share measurement": four steps over 640 ms, the body tilting
     * 7 degrees each way, then two tail wiggles of 160 ms. Plays once when the duck appears.
     */
    WaddleIn,
}

/**
 * A duck: the community. One duck per shared measurement. It faces left, as on the artboards.
 *
 * Give it a square size with [modifier]; without one it is 80 dp.
 *
 * When [LocalReduceMotion] is on it does not bob, and a duck that would waddle in fades in where it
 * stands in 150 ms.
 *
 * @param phase 0 to 1, where this duck is in the [DuckMotion.Bob] cycle relative to its neighbours.
 * @param enterFrom how far to the right a [DuckMotion.WaddleIn] duck starts; twice its width by default.
 * @param onWaddleEnd called when a [DuckMotion.WaddleIn] duck has arrived and wiggled its tail.
 */
@Composable
fun Duck(
    modifier: Modifier = Modifier,
    motion: DuckMotion = DuckMotion.Still,
    phase: Float = 0f,
    enterFrom: Dp = Dp.Unspecified,
    contentDescription: String? = "Duck",
    onWaddleEnd: () -> Unit = {},
) {
    val reduceMotion = LocalReduceMotion.current
    val clock = rememberCharacterClock(running = motion == DuckMotion.Bob && !reduceMotion)
    val waddleEnded by rememberUpdatedState(onWaddleEnd)
    var arrived by remember { mutableStateOf(false) }
    // Seconds along the waddle, or 0 to 1 of the fade when motion is reduced.
    val entrance = remember { Animatable(if (motion == DuckMotion.WaddleIn) 0f else MotionSpec.WADDLE_LENGTH) }

    LaunchedEffect(motion, reduceMotion) {
        if (motion != DuckMotion.WaddleIn) {
            arrived = false
            entrance.snapTo(MotionSpec.WADDLE_LENGTH)
            return@LaunchedEffect
        }
        if (arrived) return@LaunchedEffect
        entrance.snapTo(0f)
        if (reduceMotion) {
            entrance.animateTo(1f, CiuinMotion.reducedFade())
        } else {
            val millis = (MotionSpec.WADDLE_LENGTH * 1000).toInt()
            entrance.animateTo(MotionSpec.WADDLE_LENGTH, tween(millis, easing = LinearEasing))
        }
        arrived = true
        waddleEnded()
    }

    fun frame(): DuckFrame = when {
        motion == DuckMotion.WaddleIn && arrived -> DuckFrame()
        motion == DuckMotion.WaddleIn && reduceMotion -> DuckFrame(alpha = entrance.value.coerceAtMost(1f))
        motion == DuckMotion.WaddleIn -> waddleFrame(entrance.value)
        motion == DuckMotion.Bob && !reduceMotion -> bobFrame(clock.floatValue, phase)
        else -> DuckFrame()
    }

    val body = remember { Path() }
    val semantics = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else {
        Modifier
    }
    Canvas(
        modifier
            .defaultMinSize(80.dp, 80.dp)
            .then(semantics)
            .graphicsLayer {
                val current = frame()
                val distance = if (enterFrom.isSpecified) enterFrom.toPx() else 2f * size.width
                translationX = current.enter * distance
                alpha = current.alpha
            },
    ) {
        val current = frame()
        val pxPerUnit = min(size.width / DuckArt.WIDTH, size.height / DuckArt.HEIGHT)
        inViewBox(DuckArt.WIDTH, DuckArt.HEIGHT) {
            drawDuck(current, body, bob = current.bobDp.dp.toPx() / pxPerUnit)
        }
    }
}
