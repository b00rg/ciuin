package ch.ciuin.app.ui.characters

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/* Draws the characters part by part, in the paint order of the SVGs, applying one frame of motion. */

internal fun DrawScope.drawArt(art: ArtPath, alpha: Float = 1f) {
    art.fill?.let { drawPath(art.path, Color(it), alpha) }
    art.stroke?.let { drawPath(art.path, Color(it), alpha, style = Stroke(art.strokeWidth, cap = StrokeCap.Round)) }
}

internal fun DrawScope.drawArt(oval: ArtOval, alpha: Float = 1f) {
    drawOval(oval.cx, oval.cy, oval.rx, oval.ry, oval.rotation, Color(oval.fill), alpha)
}

private fun DrawScope.drawOval(cx: Float, cy: Float, rx: Float, ry: Float, rotation: Float, color: Color, alpha: Float = 1f) {
    val topLeft = Offset(cx - rx, cy - ry)
    val size = Size(2 * rx, 2 * ry)
    if (rotation == 0f) {
        drawOval(color, topLeft, size, alpha)
    } else {
        rotate(rotation, Offset(cx, cy)) { drawOval(color, topLeft, size, alpha) }
    }
}

/** An oval part of the way from [from] to [to]; used for the eyes widening as the ears go up. */
private fun DrawScope.drawBetween(from: ArtOval, to: ArtOval, amount: Float) {
    drawOval(
        cx = lerp(from.cx, to.cx, amount),
        cy = lerp(from.cy, to.cy, amount),
        rx = lerp(from.rx, to.rx, amount),
        ry = lerp(from.ry, to.ry, amount),
        rotation = lerp(from.rotation, to.rotation, amount),
        color = Color(from.fill),
    )
}

/**
 * Runs [block] in a drawing's own view-box coordinates, fitted inside this canvas and centred.
 * Returns nothing; the scale it used is `min(width / viewWidth, height / viewHeight)`.
 */
internal inline fun DrawScope.inViewBox(viewWidth: Float, viewHeight: Float, block: DrawScope.() -> Unit) {
    val fit = min(size.width / viewWidth, size.height / viewHeight)
    withTransform({
        translate((size.width - viewWidth * fit) / 2f, (size.height - viewHeight * fit) / 2f)
        scale(fit, fit, Offset.Zero)
    }) { block() }
}

/** Draws [block] at [alpha] as one flat picture, so overlapping parts do not show through each other. */
internal inline fun DrawScope.fading(alpha: Float, block: DrawScope.() -> Unit) {
    if (alpha <= 0f) return
    if (alpha >= 1f) {
        block()
        return
    }
    // Generous bounds: the z's drift outside the sheep's own box.
    val bounds = Rect(-size.width, -size.height, 2 * size.width, 2 * size.height)
    drawIntoCanvas { it.saveLayer(bounds, Paint().apply { this.alpha = alpha }) }
    block()
    drawIntoCanvas { it.restore() }
}

// Resting sheep.

private val restWoolCentre: Offset by lazy(LazyThreadSafetyMode.NONE) { SheepRestArt.wool.path.getBounds().center }

/**
 * Where a z is, relative to the 89 x 81 drawing, with its font size in the same units. From the Rest
 * stage on the Motion board: three z's of 14, 18 and 22 px beside a sheep drawn 164 px wide.
 */
private class ZPlace(val x: Float, val y: Float, val size: Float, val alpha: Float)

private val stillZs = listOf(
    ZPlace(94.15f, 19.34f, 7.60f, 1f),
    ZPlace(103.38f, 5.23f, 9.77f, 0.7f),
    ZPlace(114.78f, -11.05f, 11.94f, 0.35f),
)

/** @param z the letter "z" laid out once at any size, or null to leave the z's out. */
internal fun DrawScope.drawSheepRest(frame: RestFrame, z: TextLayoutResult?) {
    val art = SheepRestArt
    drawArt(art.shadow)
    art.legs.forEach { drawArt(it) }
    scale(frame.woolScale, frame.woolScale, restWoolCentre) { drawArt(art.wool) }
    translate(top = frame.headDy) {
        drawArt(art.face)
        drawArt(art.earLeft)
        drawArt(art.earRight)
        drawArt(art.eyeLeft)
        drawArt(art.eyeRight)
        drawArt(art.nose)
    }
    art.hooves.forEach { drawArt(it) }

    if (z == null) return
    val drifting = frame.zs
    if (drifting == null) {
        stillZs.forEach { drawZ(z, it.x, it.y, it.size, it.alpha) }
    } else {
        val start = stillZs.first()
        val end = stillZs.last()
        for (one in drifting) {
            drawZ(
                z = z,
                x = start.x + MotionSpec.Z_DRIFT * one.travel,
                y = start.y - MotionSpec.Z_RISE * one.travel,
                size = lerp(start.size, end.size, one.travel),
                alpha = one.alpha,
            )
        }
    }
}

private fun DrawScope.drawZ(z: TextLayoutResult, x: Float, y: Float, size: Float, alpha: Float) {
    if (alpha <= 0.01f) return
    val laidOutSize = z.layoutInput.style.fontSize.value * z.layoutInput.density.density * z.layoutInput.density.fontScale
    val k = size / laidOutSize
    withTransform({
        translate(x, y)
        scale(k, k, Offset.Zero)
    }) {
        drawText(z, topLeft = Offset(-z.size.width / 2f, -z.size.height / 2f), alpha = alpha)
    }
}

// Standing sheep: awake, ears up, hopping.

private val standBodyCentre = Offset(44f, 50f)
private val standHooves = Offset(SheepStandArt.shadow.cx, SheepStandArt.shadow.cy)
private val earLeftRoot = Offset(SheepStandArt.EAR_LEFT_PIVOT_X, SheepStandArt.EAR_LEFT_PIVOT_Y)
private val earRightRoot = Offset(SheepStandArt.EAR_RIGHT_PIVOT_X, SheepStandArt.EAR_RIGHT_PIVOT_Y)

internal fun DrawScope.drawSheepStand(frame: StandFrame) {
    val art = SheepStandArt
    scale(frame.shadowScale, frame.shadowScale, standHooves) { drawArt(art.shadow) }
    withTransform({
        translate(top = -frame.lift)
        rotate(frame.tilt, standBodyCentre)
        // Squash and stretch grow from the hooves, so the feet stay on the ground.
        scale(frame.squashX, frame.squashY, standHooves)
    }) {
        art.legs.forEach { drawArt(it) }
        scale(frame.woolScale, frame.woolScale, standBodyCentre) { drawArt(art.wool) }
        translate(top = frame.headDy) {
            rotate(frame.earLeft, earLeftRoot) { drawArt(art.earLeft) }
            rotate(frame.earRight, earRightRoot) { drawArt(art.earRight) }
            drawArt(art.face)
            drawEye(art.eyeLeft, art.alertEyeLeft, art.eyeLeftLight, art.alertEyeLeftLight, frame)
            drawEye(art.eyeRight, art.alertEyeRight, art.eyeRightLight, art.alertEyeRightLight, frame)
            if (frame.alertFace > 0f) {
                drawArt(art.browLeft, frame.alertFace)
                drawArt(art.browRight, frame.alertFace)
            }
            translate(top = SheepStandArt.ALERT_NOSE_DROP * frame.alertFace) { drawArt(art.nose) }
            drawArt(art.forelock)
        }
        art.marks.forEachIndexed { index, mark -> if (frame.marks[index] > 0f) drawArt(mark, frame.marks[index]) }
    }
}

private fun DrawScope.drawEye(pupil: ArtOval, alertPupil: ArtOval, light: ArtOval, alertLight: ArtOval, frame: StandFrame) {
    val centre = Offset(pupil.cx, lerp(pupil.cy, alertPupil.cy, frame.alertFace))
    scale(1f, frame.eyeOpen, centre) {
        drawBetween(pupil, alertPupil, frame.alertFace)
        drawBetween(light, alertLight, frame.alertFace)
    }
}

// Reading sheep.

private val readWoolCentre: Offset by lazy(LazyThreadSafetyMode.NONE) { SheepReadArt.wool.path.getBounds().center }
private val pageEdge = Color(0xFFCECBC9)

internal fun DrawScope.drawSheepRead(frame: ReadFrame) {
    val art = SheepReadArt
    scale(frame.woolScale, frame.woolScale, readWoolCentre) { drawArt(art.wool) }
    art.limbs.forEach { drawArt(it) }
    drawArt(art.bookCover)
    drawArt(art.bookSpine)
    drawArt(art.bookPages)
    if (frame.pageTurn in 0f..1f) drawTurningPage(frame.pageTurn)
    art.hooves.forEach { drawArt(it) }
    translate(top = frame.headDy) {
        drawArt(art.earLeft)
        drawArt(art.earRight)
        drawArt(art.face)
        translate(frame.eyeDx, frame.eyeDy) {
            drawArt(art.eyeLeft)
            drawArt(art.eyeLeftLight)
            drawArt(art.eyeRight)
            drawArt(art.eyeRightLight)
        }
        drawArt(art.nose)
    }
}

/**
 * The top edge of one page swinging over the spine from the right block to the left. We see the book
 * from behind, so the page shows only as that edge. Not in Paper: the board describes the page turn
 * in words only.
 */
private fun DrawScope.drawTurningPage(progress: Float) {
    val spine = Offset(SheepReadArt.SPINE_TOP_X, SheepReadArt.SPINE_TOP_Y)
    val right = Offset(SheepReadArt.PAGE_RIGHT_TIP_X, SheepReadArt.PAGE_RIGHT_TIP_Y) - spine
    val left = Offset(SheepReadArt.PAGE_LEFT_TIP_X, SheepReadArt.PAGE_LEFT_TIP_Y) - spine
    val angle = lerp(atan2(right.y, right.x), atan2(left.y, left.x), progress)
    val length = lerp(right.getDistance(), left.getDistance(), progress)
    val tip = spine + Offset(cos(angle), sin(angle)) * length
    // The page bows as it goes over: a curve whose middle leans towards where it came from.
    val bow = 2.4f * sin(PI.toFloat() * progress)
    val middle = (spine + tip) / 2f + Offset(-sin(angle), cos(angle)) * -bow
    turningPage.rewind()
    turningPage.moveTo(spine.x, spine.y)
    turningPage.quadraticTo(middle.x, middle.y, tip.x, tip.y)
    drawPath(turningPage, pageEdge, style = Stroke(2.2f, cap = StrokeCap.Round))
    drawPath(turningPage, Color.White, style = Stroke(1.4f, cap = StrokeCap.Round))
}

private val turningPage = Path()

// Duck.

private val duckFeet = Offset(DuckArt.shadow.cx, 73f)
private val duckBodyColor = Color(DuckArt.BODY_COLOR)

/**
 * @param body a scratch path this function rebuilds each frame for the wiggling tail.
 * @param bob how far the duck floats up (negative) or down, in view-box units; the shadow stays put.
 */
internal fun DrawScope.drawDuck(frame: DuckFrame, body: Path, bob: Float) {
    val art = DuckArt
    drawArt(art.shadow)
    withTransform({
        translate(top = bob)
        rotate(frame.tilt, duckFeet)
    }) {
        drawArt(art.legs)
        art.feet.forEach { drawArt(it) }
        buildDuckBody(body, frame.tail)
        drawPath(body, duckBodyColor)
        drawArt(art.wing)
        drawArt(art.tuft)
        drawArt(art.head)
        drawArt(art.beak)
        drawArt(art.beakLine)
        drawArt(art.eye)
        drawArt(art.eyeLight)
    }
}

/** The duck's body path with its three tail points turned [tailDegrees] about the tail's root. */
private fun buildDuckBody(path: Path, tailDegrees: Float) {
    val radians = tailDegrees * (PI.toFloat() / 180f)
    val cosine = cos(radians)
    val sine = sin(radians)
    fun turnX(x: Float, y: Float) = DuckArt.TAIL_PIVOT_X + (x - DuckArt.TAIL_PIVOT_X) * cosine - (y - DuckArt.TAIL_PIVOT_Y) * sine
    fun turnY(x: Float, y: Float) = DuckArt.TAIL_PIVOT_Y + (x - DuckArt.TAIL_PIVOT_X) * sine + (y - DuckArt.TAIL_PIVOT_Y) * cosine
    path.rewind()
    path.moveTo(20f, 50f)
    path.cubicTo(20f, 38f, 32f, 36f, 44f, 38f)
    path.cubicTo(54f, 39.5f, turnX(60f, 38f), turnY(60f, 38f), turnX(66.5f, 30.5f), turnY(66.5f, 30.5f))
    path.cubicTo(turnX(70f, 40f), turnY(70f, 40f), 69f, 52f, 60.5f, 59.5f)
    path.cubicTo(52f, 66.5f, 36f, 67.5f, 28f, 63.5f)
    path.cubicTo(22f, 60.5f, 20f, 56f, 20f, 50f)
    path.close()
}
