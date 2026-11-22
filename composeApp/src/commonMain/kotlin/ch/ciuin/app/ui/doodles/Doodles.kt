package ch.ciuin.app.ui.doodles

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import ch.ciuin.app.ui.theme.CiuinColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Crayon colours for the doodles. Never for text and never for a level: a doodle means nothing, so
 * its colours stay clear of calm, moderate and loud. Each one shows on [CiuinColors.Wool] and on
 * [CiuinColors.Ground].
 */
object CrayonColors {
    /** Suns, sparkles and the moon: the sheep's hoof yellow. */
    val Yellow: Color get() = CiuinColors.Hoof

    /** Leaves and squiggles: sage, deep enough to show on mist. */
    val Sage = Color(0xFF7C9A93)

    /** Clouds and water: mist blue, deep enough to show on the mist cards. */
    val Blue = Color(0xFF8593AD)
}

/**
 * The hand-drawn doodles, each an open crayon line on a 24 grid. They are decoration only: they sit
 * around the characters (onboarding, the shared measurement, the pond on the You tab, empty states)
 * and never stand for an action or a level. Functional icons stay [ch.ciuin.app.ui.icons.CiuinIcons].
 *
 * Lines are left a little open and uneven on purpose, as a crayon leaves them.
 */
enum class Doodle(pathData: String, internal val crayon: () -> Color) {
    Sun(
        "M16.4 11.6C16.6 14.2 14.6 16.5 12.1 16.6C9.5 16.7 7.4 14.7 7.4 12.1C7.4 9.6 9.4 7.5 11.9 7.4C14.2 7.3 16 8.9 16.5 10.8" +
            "M12 2.6V4.5M12.1 19.5V21.4M2.6 12.2H4.6M19.5 11.8H21.4M5.3 5.4L6.6 6.7M17.4 17.4L18.7 18.7M18.8 5.2L17.4 6.5M6.6 17.4L5.2 18.8",
        { CrayonColors.Yellow },
    ),
    Sparkle(
        "M12 2.6C12.6 7.7 14 10.8 21 12C14.1 13.1 12.7 16.2 12 21.4C11.3 16.3 9.9 13.2 3 12.1C10 10.9 11.3 7.9 11.8 3.4",
        { CrayonColors.Yellow },
    ),
    Wave(
        "M2.6 13.8C4.2 10.6 6.3 10.4 7.9 13.4C9.6 16.5 11.6 16.3 13.2 13.1C14.8 9.9 17 9.8 18.6 12.9C19.5 14.6 20.6 15.1 21.5 14.4",
        { CrayonColors.Blue },
    ),
    Squiggle(
        "M2.8 15.4C5 15.6 5.4 9 7.9 9.1C10.2 9.2 8.5 14.9 10.7 14.9C12.9 14.9 12 8.6 14.4 8.7C16.6 8.8 15.4 14.6 17.6 14.6C19.3 14.6 19.7 11.1 21.2 10.4",
        { CrayonColors.Sage },
    ),
    Moon(
        "M14.6 3.2C9.4 3.9 5.6 8.6 6.2 13.8C6.8 18.6 11.4 21.7 16.2 20.9C17.7 20.6 19 20 20 19.1C15.2 19.2 11.4 16 11 11.4C10.7 8 12.1 5.1 14.3 3.5",
        { CrayonColors.Yellow },
    ),
    Cloud(
        "M7 18.2C4.6 18.2 2.8 16.5 2.8 14.3C2.8 12.2 4.5 10.6 6.6 10.5C7.1 7.6 9.5 5.6 12.4 5.6C15.3 5.6 17.6 7.7 18 10.4C20 10.6 21.4 12.3 21.3 14.3C21.2 16.5 19.6 18.1 17.3 18.1L7.6 18.3",
        { CrayonColors.Blue },
    ),
    Leaf(
        "M5 19C4.6 12.4 8.4 5.6 19.6 4.4C20.4 13.8 14.6 19.2 6.6 18.9M5 19C8.2 14.6 11.4 11.4 15.6 8.6",
        { CrayonColors.Sage },
    ),
    ;

    internal val path: Path by lazy(LazyThreadSafetyMode.NONE) { PathParser().parsePathString(pathData).toPath() }
}

/** Width of a doodle's line on its 24 grid: a soft crayon, heavier than an icon's 1.5. */
const val DOODLE_STROKE = 1.9f

/**
 * Draws [doodle] filling the smaller side of its box, in its own crayon unless [color] says otherwise.
 * It has no semantics: a doodle is decoration and says nothing to a screen reader.
 *
 * @param rotation degrees, so a scattered set does not all sit square.
 * @param seed picks a different grain, so two of the same doodle are not identical.
 */
@Composable
fun CrayonDoodle(
    doodle: Doodle,
    modifier: Modifier = Modifier,
    color: Color = doodle.crayon(),
    rotation: Float = 0f,
    seed: Int = doodle.ordinal,
) {
    Spacer(
        modifier.drawBehind {
            val k = min(size.width, size.height) / DOODLE_GRID
            translate((size.width - DOODLE_GRID * k) / 2f, (size.height - DOODLE_GRID * k) / 2f) {
                scale(k, k, Offset.Zero) {
                    rotate(rotation, Offset(DOODLE_GRID / 2f, DOODLE_GRID / 2f)) {
                        drawCrayon(doodle.path, color, DOODLE_STROKE, seed)
                    }
                }
            }
        },
    )
}

private const val DOODLE_GRID = 24f

/**
 * Strokes [path] like a crayon on rough paper: a waxy line, a lighter second pass a little off the
 * first, and fine streaks of paper showing through along the line. [width] is in the current units;
 * [seed] shifts where the streaks fall.
 */
fun DrawScope.drawCrayon(path: Path, color: Color, width: Float, seed: Int = 0) {
    val bounds = path.getBounds().inflate(width * 2f)
    drawIntoCanvas { it.saveLayer(bounds, Paint()) }

    drawPath(path, color, alpha = 0.9f, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    translate(width * 0.26f, -width * 0.2f) {
        drawPath(path, color, alpha = 0.45f, style = Stroke(width * 0.62f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    // Paper grain: thin dashed lines just off the centre in four directions, so a stroke going any
    // way shows streaks, cut out of the wax rather than painted over it.
    for ((i, grain) in Grain.withIndex()) {
        val intervals = FloatArray(grain.pattern.size) { grain.pattern[it] * width }
        val phase = ((seed * 7 + i * 3) % 11) * width * 0.37f
        translate(grain.dx * width, grain.dy * width) {
            drawPath(
                path = path,
                color = Color.Black,
                style = Stroke(width * grain.thickness, cap = StrokeCap.Butt, pathEffect = PathEffect.dashPathEffect(intervals, phase)),
                blendMode = BlendMode.DstOut,
            )
        }
    }

    drawIntoCanvas { it.restore() }
}

/** One set of grain streaks: where it sits off the centre line, how thick it is, and its dashes (on, off, ...). */
private class GrainPass(val dx: Float, val dy: Float, val thickness: Float, val pattern: FloatArray)

private val Grain = listOf(
    GrainPass(-0.34f, -0.3f, 0.11f, floatArrayOf(0.22f, 0.41f, 0.13f, 0.77f, 0.35f, 0.29f, 0.18f, 1.1f)),
    GrainPass(0.33f, 0.35f, 0.1f, floatArrayOf(0.16f, 0.58f, 0.31f, 0.33f, 0.12f, 0.92f)),
    GrainPass(-0.33f, 0.31f, 0.1f, floatArrayOf(0.27f, 0.36f, 0.14f, 0.69f, 0.21f, 0.47f)),
    GrainPass(0.35f, -0.32f, 0.11f, floatArrayOf(0.13f, 0.52f, 0.24f, 0.31f, 0.38f, 0.83f)),
    GrainPass(-0.15f, -0.12f, 0.08f, floatArrayOf(0.11f, 0.95f, 0.19f, 0.64f, 0.09f, 1.3f)),
    GrainPass(0.16f, 0.14f, 0.08f, floatArrayOf(0.17f, 0.73f, 0.1f, 1.21f, 0.14f, 0.58f)),
    GrainPass(0.14f, -0.17f, 0.08f, floatArrayOf(0.12f, 1.05f, 0.2f, 0.81f)),
    GrainPass(-0.17f, 0.15f, 0.08f, floatArrayOf(0.15f, 0.62f, 0.1f, 1.37f, 0.18f, 0.9f)),
)

/**
 * A quick circle scribbled around [center]: one loop and a little more, slightly egg-shaped and not
 * quite closing, as if drawn by hand around the selected place on the map. [radius] is the loop's
 * mean radius; the line is [width] wide in [color].
 */
fun DrawScope.drawScribbleCircle(center: Offset, radius: Float, color: Color, width: Float) {
    val path = Path()
    val steps = 72
    val turns = 1.14f
    val start = (-PI * 0.62).toFloat()
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val angle = start + t * turns * 2f * PI.toFloat()
        // Wobble a little, and drift outwards so the overlap shows as a second line, not a thick one.
        val r = radius * (1f + 0.04f * sin(2f * angle + 0.7f) + 0.025f * sin(5f * angle) + 0.09f * t)
        val x = center.x + r * 1.07f * cos(angle)
        val y = center.y + r * 0.95f * sin(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawCrayon(path, color, width, seed = 3)
}
