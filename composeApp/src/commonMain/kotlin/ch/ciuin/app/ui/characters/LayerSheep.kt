package ch.ciuin.app.ui.characters

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.LocalReduceMotion

/** The map layers that each have their own sheep in the corner of the layer sheet. Access keeps its wheelchair disc. */
enum class LayerCharacter {
    /** The sheep in headphones. */
    Sound,

    /** The sheep under a streetlight. Also the flicker view, which is part of the Light layer. */
    Light,

    /** Three sheep together. */
    Crowd,
}

/**
 * The sheep for a map layer, drawn to fill a [CharacterTile][ch.ciuin.app.ui.components.CharacterTile]
 * (72 dp, or 84 dp for an empty layer). Decorative: the eyebrow beside it already names the layer.
 * Only the sheep and the streetlight's glow move, both slowly, and both hold still under reduce motion.
 */
@Composable
fun BoxScope.LayerSheep(character: LayerCharacter) {
    when (character) {
        LayerCharacter.Sound ->
            Sheep(SheepPose.Awake, Modifier.size(62.dp, 56.dp), contentDescription = null, headphones = true)

        LayerCharacter.Light -> {
            Streetlight(Modifier.align(Alignment.Center).offset(x = 14.dp).size(26.dp, 56.dp))
            Sheep(
                SheepPose.Awake,
                Modifier.align(Alignment.Center).offset(x = (-9).dp, y = 7.dp).size(44.dp),
                contentDescription = null,
            )
        }

        LayerCharacter.Crowd -> {
            Sheep(
                SheepPose.Awake,
                Modifier.align(Alignment.Center).offset(x = (-13).dp, y = (-9).dp).size(32.dp),
                contentDescription = null,
            )
            Sheep(
                SheepPose.Awake,
                Modifier.align(Alignment.Center).offset(x = 14.dp, y = (-10).dp).size(30.dp),
                contentDescription = null,
            )
            Sheep(
                SheepPose.Awake,
                Modifier.align(Alignment.Center).offset(y = 8.dp).size(42.dp),
                contentDescription = null,
            )
        }
    }
}

/**
 * A slate streetlight with its arm reaching left and a soft pool of warm light under the lamp. The
 * glow breathes slowly, about 3 s out and back, never a flicker; under reduce motion it holds at its middle.
 */
@Composable
private fun Streetlight(modifier: Modifier) {
    val pulse = if (LocalReduceMotion.current) {
        null
    } else {
        rememberInfiniteTransition(label = "streetlight glow").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(GLOW_HALF_PERIOD_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "glow",
        )
    }
    Canvas(modifier) {
        val unit = size.width / 26f
        val ink = CiuinColors.Ink
        val stroke = 2.2f * unit
        // The glow first, so the lamp sits on it. Read here, in drawing, so the pulse only redraws.
        val glow = pulse?.value ?: 0.5f
        drawCircle(
            CiuinColors.Hoof.copy(alpha = 0.14f + 0.16f * glow),
            radius = (8f + 2f * glow) * unit,
            center = Offset(8f * unit, 13f * unit),
        )
        // Pole and base.
        drawLine(ink, Offset(19f * unit, 55f * unit), Offset(19f * unit, 9f * unit), stroke, StrokeCap.Round)
        drawLine(ink, Offset(15f * unit, 55f * unit), Offset(23f * unit, 55f * unit), stroke, StrokeCap.Round)
        // The arm curls over to the left.
        val arm = Path().apply {
            moveTo(19f * unit, 9f * unit)
            cubicTo(19f * unit, 3f * unit, 9f * unit, 2f * unit, 8f * unit, 8f * unit)
        }
        drawPath(arm, ink, style = Stroke(stroke, cap = StrokeCap.Round))
        // The lamp: a small shade with the warm bulb under it.
        drawRoundRect(
            ink,
            topLeft = Offset(4.5f * unit, 7.5f * unit),
            size = Size(7f * unit, 3.2f * unit),
            cornerRadius = CornerRadius(1.6f * unit),
        )
        drawCircle(CiuinColors.Hoof, radius = 2f * unit, center = Offset(8f * unit, 11.6f * unit))
    }
}

/** Half of one slow breath of the streetlight's glow: brightening, then the same back. */
private const val GLOW_HALF_PERIOD_MS = 1600
