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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.LocalReduceMotion

/** The map layers that each have their own sheep in the corner of the layer sheet. */
enum class LayerCharacter {
    /** The sheep in headphones. */
    Sound,

    /** The sheep under a streetlight. Also the flicker view, which is part of the Light layer. */
    Light,

    /** Three sheep together. */
    Crowd,

    /** The sheep in a wheelchair. */
    Access,
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

        LayerCharacter.Access -> AccessSheep()

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

/**
 * The sheep sitting in its wheelchair like a person: rear on the seat and back against the backrest, front
 * hooves resting on the armrest, back legs hanging over the front of the seat, looking out. The chair faces
 * front-left (casters at the front, the big wheel behind). App sheep, slate chair. Holds still.
 */
@Composable
internal fun BoxScope.AccessSheep() {
    Canvas(Modifier.align(Alignment.Center).offset(y = 2.dp).size(66.dp, 62.dp)) { drawSeatedSheep() }
}

/** Draws the seated sheep and its chair in a 100 x 94 design space scaled to the canvas. */
private fun DrawScope.drawSeatedSheep() {
    val u = size.width / 100f
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    val ink = CiuinColors.Ink
    val line = 3.2f * u
    val legColour = Color(0xFFD9D6D3)
    val hoofColour = Color(0xFFE9AB22)

    // Behind the sheep: the big wheel with its push rim, the backrest with its handle, the seat.
    drawCircle(ink, radius = 19f * u, center = p(64f, 70f), style = Stroke(line))
    drawCircle(ink, radius = 13f * u, center = p(64f, 70f), style = Stroke(1.6f * u))
    drawCircle(ink, radius = 2.4f * u, center = p(64f, 70f))
    drawLine(ink, p(72f, 58f), p(78f, 20f), line, StrokeCap.Round)
    drawLine(ink, p(78f, 20f), p(89f, 18f), line, StrokeCap.Round)
    drawLine(ink, p(32f, 58f), p(72f, 58f), line, StrokeCap.Round)

    // Back legs hang over the front of the seat, hooves towards the footrest.
    fun leg(from: Offset, to: Offset) {
        drawLine(legColour, from, to, 5.2f * u, StrokeCap.Round)
        drawOval(hoofColour, topLeft = to - Offset(3.6f * u, 2.2f * u), size = Size(7.2f * u, 4.6f * u))
    }
    leg(p(42f, 57f), p(37f, 74f))
    leg(p(51f, 58f), p(48f, 76f))

    // The sheep itself (the app's standing sheep without its legs), sat upright: rear on the seat, head up.
    val art = SheepStandArt
    withTransform({
        translate(left = 52f * u, top = 40f * u)
        rotate(SEATED_TILT, Offset.Zero)
        scale(0.82f * u, 0.82f * u, Offset.Zero)
        translate(left = -46f, top = -46f)
    }) {
        drawArt(art.wool)
        drawArt(art.earLeft)
        drawArt(art.earRight)
        drawArt(art.face)
        drawArt(art.eyeLeft)
        drawArt(art.eyeLeftLight)
        drawArt(art.eyeRight)
        drawArt(art.eyeRightLight)
        drawArt(art.nose)
        drawArt(art.forelock)
    }

    // In front: the armrest, with the front hooves resting on it.
    drawLine(ink, p(36f, 45f), p(66f, 45f), line, StrokeCap.Round)
    drawLine(ink, p(40f, 45f), p(40f, 58f), line, StrokeCap.Round)
    leg(p(32f, 40f), p(39f, 42.5f))
    leg(p(43f, 41f), p(50f, 42.5f))

    // Front frame down to the footrest, and the front caster.
    drawLine(ink, p(36f, 58f), p(30f, 81f), line, StrokeCap.Round)
    drawLine(ink, p(30f, 81f), p(18f, 81f), line, StrokeCap.Round)
    drawCircle(ink, radius = 4.6f * u, center = p(31f, 88f), style = Stroke(line))
}

/** How far the sheep's body is turned up from standing to sitting, in degrees (head up, rear down). */
private const val SEATED_TILT = 24f

/** Half of one slow breath of the streetlight's glow: brightening, then the same back. */
private const val GLOW_HALF_PERIOD_MS = 1600
