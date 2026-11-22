package ch.ciuin.app.ui.characters

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.ciuin.app.ui.components.LabelPill
import ch.ciuin.app.ui.components.LabelPillStyle
import ch.ciuin.app.ui.components.Level
import ch.ciuin.app.ui.doodles.CrayonDoodle
import ch.ciuin.app.ui.doodles.Doodle
import ch.ciuin.app.ui.components.LevelPill
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinRadii

/**
 * Places things in a scene by the numbers on the artboard. A scene is drawn 342 wide in Paper and
 * scales with its width, so one [unit] is one artboard pixel at the scene's current size.
 */
@Stable
class SceneScope internal constructor(val unit: Dp, box: BoxScope) : BoxScope by box {
    /**
     * Puts an element where the artboard has it: [left] and [top] of its box, and its size. The
     * element may hang over the scene's edge, as the ground curve does; the scene clips it.
     */
    fun Modifier.frame(left: Float, top: Float, width: Float, height: Float = width): Modifier =
        layout { measurable, _ ->
            val size = Constraints.fixed((unit * width).roundToPx(), (unit * height).roundToPx())
            val placeable = measurable.measure(size)
            layout(0, 0) { placeable.place((unit * left).roundToPx(), (unit * top).roundToPx()) }
        }

    /**
     * An ellipse filling the frame: the ground curve the sheep stands on ([CiuinColors.Pasture]) and
     * the pond ([CiuinColors.Pond]).
     */
    @Composable
    fun Oval(color: Color, left: Float, top: Float, width: Float, height: Float) {
        Box(Modifier.frame(left, top, width, height).drawBehind { drawOval(color) })
    }
}

/**
 * The rounded wool panel that every character scene sits in, clipped at its 40 dp corners. It fills
 * the width it is given and keeps the artboard's proportions: [designHeight] is the panel's height
 * on a 342-wide artboard (330 on the first onboarding screen, 210 on the alert).
 *
 * Use the ready scenes below where one fits; build on this for a new one.
 */
@Composable
fun Scene(
    designHeight: Float,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = CiuinRadii.Scene,
    contentDescription: String? = null,
    content: @Composable SceneScope.() -> Unit,
) {
    val semantics = if (contentDescription != null) {
        Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio(SCENE_WIDTH / designHeight)
            .clip(RoundedCornerShape(cornerRadius))
            .background(CiuinColors.Wool)
            .then(semantics),
    ) {
        val unit = maxWidth / SCENE_WIDTH
        val scope = remember(unit, this) { SceneScope(unit, this) }
        scope.content()
    }
}

private const val SCENE_WIDTH = 342f

/** The ground curve: an ellipse 80 wider than the scene whose top edge is [top] below the scene's top. */
@Composable
private fun SceneScope.Ground(top: Float) {
    Oval(CiuinColors.Pasture, left = -40f, top = top, width = 422f, height = 200f)
}

/**
 * The sheep on its pasture with two ducks: the picture on the first onboarding screen, "Find your
 * quiet." 342 by 330 on the artboard.
 */
@Composable
fun WelcomeScene(modifier: Modifier = Modifier, pose: SheepPose = SheepPose.Awake) {
    Scene(330f, modifier, contentDescription = "A sheep on a pasture with two ducks") {
        CrayonDoodle(Doodle.Sun, Modifier.frame(274f, 18f, 50f), rotation = 8f)
        CrayonDoodle(Doodle.Cloud, Modifier.frame(16f, 30f, 54f))
        CrayonDoodle(Doodle.Sparkle, Modifier.frame(28f, 148f, 22f), rotation = -10f)
        Ground(top = 230f)
        Sheep(pose, Modifier.frame(58f, 42f, 226f), contentDescription = null)
        Duck(Modifier.frame(236f, 214f, 74f), contentDescription = null)
        Duck(Modifier.frame(20f, 238f, 50f), contentDescription = null)
    }
}

/**
 * The sheep hopping onto the pasture, three ducks beside it: the last onboarding screen, "You're all
 * set." 342 by 270 on the artboard. The hop plays once when the scene appears.
 *
 * The artboard freezes the sheep in the air, about 40 px up and tilted 6 degrees. The Motion board
 * gives the hop as 7 pt, so here the sheep hops that much, tilting at the top, and lands on the curve.
 */
@Composable
fun AllSetScene(modifier: Modifier = Modifier, onHopEnd: () -> Unit = {}) {
    Scene(270f, modifier, contentDescription = "The sheep hops; three ducks stand beside it") {
        Ground(top = 196f)
        HopArc(Modifier.frame(26f, 176f, 120f, 40f))
        CrayonDoodle(Doodle.Sparkle, Modifier.frame(40f, 56f, 30f), rotation = -8f)
        CrayonDoodle(Doodle.Sparkle, Modifier.frame(66f, 112f, 16f), rotation = 12f, seed = 9)
        CrayonDoodle(Doodle.Sparkle, Modifier.frame(286f, 38f, 24f), rotation = 6f, seed = 4)
        Sheep(SheepPose.Hop, Modifier.frame(84f, 32f, 190f), contentDescription = null, onHopEnd = onHopEnd)
        Duck(Modifier.frame(214f, 186f, 60f), contentDescription = null)
        Duck(Modifier.frame(258f, 200f, 44f), contentDescription = null)
        Duck(Modifier.frame(292f, 210f, 34f), contentDescription = null)
    }
}

/**
 * The sheep with its ears up and the reading that set it off: the picture on the alert, "It's
 * getting loud here." 342 by 210 on the artboard. The ears lift once when the scene appears.
 *
 * @param status the words in the white pill at the top left, such as "Too much · 74 dB".
 */
@Composable
fun AlertScene(
    status: String,
    modifier: Modifier = Modifier,
    level: Level = Level.Loud,
    haptic: Boolean = true,
    earsRaised: Boolean = true,
) {
    val pose = if (earsRaised) SheepPose.Alert else SheepPose.Awake
    Scene(210f, modifier, contentDescription = "${pose.description}. $status") {
        Ground(top = 150f)
        Sheep(pose, Modifier.frame(82f, 32f, 178f), contentDescription = null, haptic = haptic)
        LevelPill(level, status, Modifier.absoluteOffset(16.dp, 16.dp))
    }
}

/**
 * The sheep hopping among sparkles after a measurement is shared: "Shared. Thanks for adding to the
 * map." 342 by 250 on the artboard. The sheep hops once.
 */
@Composable
fun SharedScene(modifier: Modifier = Modifier) {
    Scene(250f, modifier, contentDescription = "The sheep hops among sparkles") {
        Ground(top = 196f)
        CrayonDoodle(Doodle.Sparkle, Modifier.frame(44f, 46f, 30f), rotation = -8f)
        CrayonDoodle(Doodle.Sparkle, Modifier.frame(70f, 110f, 16f), rotation = 12f, seed = 9)
        CrayonDoodle(Doodle.Sparkle, Modifier.frame(270f, 36f, 26f), rotation = 6f, seed = 4)
        CrayonDoodle(Doodle.Sparkle, Modifier.frame(292f, 112f, 16f), rotation = -10f, seed = 5)
        Sheep(SheepPose.Hop, Modifier.frame(80f, 24f, 182f), contentDescription = null)
    }
}

/**
 * The sheep and its pond on the You tab: one duck for every measurement you have shared, floating at
 * different points in the same cycle. 342 by 188 with 32 dp corners.
 *
 * Paper leaves this card as an empty panel on the You artboards; the layout here is put together
 * from the pond on the Motion board and the "Shared" scene. It shows up to five ducks.
 */
@Composable
fun PondScene(ducks: Int, modifier: Modifier = Modifier, pose: SheepPose = SheepPose.Rest) {
    val description = when (ducks) {
        0 -> "Your sheep beside an empty pond"
        1 -> "Your sheep and one duck on the pond"
        else -> "Your sheep and $ducks ducks on the pond"
    }
    Scene(188f, modifier, cornerRadius = CiuinRadii.SceneSmall, contentDescription = description) {
        Oval(CiuinColors.Pond, left = 136f, top = 104f, width = 230f, height = 68f)
        // A moon while the sheep sleeps, a sun while it listens.
        val sky = if (pose == SheepPose.Rest) Doodle.Moon else Doodle.Sun
        CrayonDoodle(sky, Modifier.frame(294f, 12f, 34f), rotation = -12f)
        CrayonDoodle(Doodle.Cloud, Modifier.frame(190f, 10f, 40f), seed = 6)
        CrayonDoodle(Doodle.Wave, Modifier.frame(298f, 138f, 26f), seed = 3)
        val sheepHeight = if (pose == SheepPose.Rest) 112f * SheepRestArt.HEIGHT / SheepRestArt.WIDTH else 112f
        Sheep(pose, Modifier.frame(20f, 150f - sheepHeight, 112f, sheepHeight), contentDescription = null)
        val shown = PondDucks.take(ducks.coerceIn(0, PondDucks.size))
        val phaseCount = maxOf(3, shown.size).toFloat()
        shown.forEachIndexed { index, place ->
            Duck(
                modifier = Modifier.frame(place.left, place.top, place.size),
                motion = DuckMotion.Bob,
                phase = index / phaseCount,
                contentDescription = null,
            )
        }
    }
}

private class DuckPlace(val left: Float, val top: Float, val size: Float)

/** Front to back, so a pond with few ducks fills from the middle. */
private val PondDucks = listOf(
    DuckPlace(168f, 78f, 62f),
    DuckPlace(236f, 92f, 46f),
    DuckPlace(284f, 84f, 40f),
    DuckPlace(212f, 60f, 34f),
    DuckPlace(264f, 58f, 30f),
)

/**
 * The dotted arc of a hop: "the sheep just jumped from there". 120 by 40 on the artboard, to the
 * left of the sheep on the last onboarding screen.
 */
@Composable
fun HopArc(modifier: Modifier = Modifier) {
    val arc = remember { Path() }
    Box(
        modifier.drawBehind {
            val k = size.width / 120f
            scale(k, k, Offset.Zero) {
                arc.rewind()
                arc.moveTo(4f, 36f)
                arc.cubicTo(24f, 4f, 52f, 4f, 72f, 36f)
                drawPath(
                    path = arc,
                    color = CiuinColors.DottedLine,
                    style = Stroke(2f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 7f))),
                )
            }
        },
    )
}
