package ch.ciuin.app.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import ch.ciuin.app.ui.characters.Scene
import ch.ciuin.app.ui.characters.Sheep
import ch.ciuin.app.ui.characters.SheepPose
import ch.ciuin.app.ui.components.LevelLegendCard
import ch.ciuin.app.ui.theme.CiuinColors

/**
 * The picture on the second onboarding screen, "See how a place feels at a glance": a honeycomb of
 * calm, moderate and loud cells, a wool hill, the reading sheep, and the white legend card. 342 by
 * 330 on the artboard; like every scene it scales with its width.
 */
@Composable
internal fun HowItReadsScene(modifier: Modifier = Modifier) {
    Scene(330f, modifier, contentDescription = HOW_IT_READS_DESCRIPTION) {
        Box(Modifier.fillMaxSize().drawWithCache {
            val unit = size.width / SCENE_WIDTH
            val paths = HoneycombCells.map { (color, cells) -> color to hexPath(cells, unit) }
            onDrawBehind { for ((color, path) in paths) drawPath(path, color.copy(alpha = HEX_ALPHA)) }
        })
        Oval(CiuinColors.Wool, left = -40f, top = 218f, width = 422f, height = 200f)
        Sheep(SheepPose.Reading, Modifier.frame(20f, 150f, 168f), contentDescription = null)
        LevelLegendCard(Modifier.align(Alignment.BottomEnd).padding(end = unit * 16f, bottom = unit * 20f))
    }
}

private const val HOW_IT_READS_DESCRIPTION =
    "A honeycomb of green, amber and red areas. A sheep reads beside the legend: " +
        "Calm is a green circle, Moderate an amber diamond, Loud a red square."

private const val SCENE_WIDTH = 342f

/**
 * The artboard draws the cells at 85% inside a layer at 80%. The cells never overlap, so one alpha
 * per cell gives the same pixels.
 */
private const val HEX_ALPHA = 0.85f * 0.8f

/**
 * The honeycomb image is 342 by 230, fitted into a 342 by 411 box that starts 81 above the scene,
 * so it is centred 9.5 below the scene's top. Its cells hang past every edge; the scene clips them.
 */
private const val HONEYCOMB_TOP = -81f + (411f - 230f) / 2f

/** Pointy-top cells with sides of 21.1: half width 18.3, shoulders 10.6 and 31.7 below the tip, 42.3 tall. */
private const val HALF_WIDTH = 18.3f
private const val SHOULDER = 10.6f
private const val SIDE = 21.1f

/** The tip of each cell (x, y in the honeycomb image), per level colour, from the artboard. */
private val HoneycombCells: List<Pair<Color, FloatArray>> = listOf(
    CiuinColors.Calm to floatArrayOf(
        -8.3f, -25.9f, 33.3f, -25.9f, 74.8f, -25.9f, 116.4f, -25.9f, 12.5f, 10.1f, 54.0f, 10.1f, 95.6f, 10.1f,
        -8.3f, 46.1f, 33.3f, 46.1f, 12.5f, 82.1f, 54.0f, 82.1f, -8.3f, 118.1f, 33.3f, 118.1f, 12.5f, 154.1f,
        54.0f, 154.1f, 95.6f, 154.1f, -8.3f, 190.1f, 33.3f, 190.1f, 74.8f, 190.1f, 116.4f, 190.1f, 241.1f, 190.1f,
        324.2f, 190.1f, 365.8f, 190.1f, 12.5f, 226.1f, 54.0f, 226.1f, 95.6f, 226.1f, 137.2f, 226.1f, 178.7f, 226.1f,
        220.3f, 226.1f, 303.5f, 226.1f, 345.0f, 226.1f,
    ),
    CiuinColors.Moderate to floatArrayOf(
        158.0f, -25.9f, 324.2f, -25.9f, 365.8f, -25.9f, 137.2f, 10.1f, 345.0f, 10.1f, 74.8f, 46.1f, 116.4f, 46.1f,
        158.0f, 46.1f, 365.8f, 46.1f, 95.6f, 82.1f, 137.2f, 82.1f, 178.7f, 82.1f, 220.3f, 82.1f, 303.5f, 82.1f,
        345.0f, 82.1f, 74.8f, 118.1f, 116.4f, 118.1f, 158.0f, 118.1f, 199.5f, 118.1f, 241.1f, 118.1f, 282.7f, 118.1f,
        324.2f, 118.1f, 365.8f, 118.1f, 137.2f, 154.1f, 178.7f, 154.1f, 220.3f, 154.1f, 261.9f, 154.1f, 303.5f, 154.1f,
        345.0f, 154.1f, 158.0f, 190.1f, 199.5f, 190.1f, 282.7f, 190.1f, 261.9f, 226.1f,
    ),
    CiuinColors.Loud to floatArrayOf(
        199.5f, -25.9f, 241.1f, -25.9f, 282.7f, -25.9f, 178.7f, 10.1f, 220.3f, 10.1f, 261.9f, 10.1f, 303.5f, 10.1f,
        199.5f, 46.1f, 241.1f, 46.1f, 282.7f, 46.1f, 324.2f, 46.1f, 261.9f, 82.1f,
    ),
)

/** All [cells] of one colour as a single path, in pixels at [unit] pixels per artboard point. */
private fun hexPath(cells: FloatArray, unit: Float): Path = Path().apply {
    for (index in cells.indices step 2) {
        val x = cells[index]
        val y = cells[index + 1] + HONEYCOMB_TOP
        moveTo(x * unit, y * unit)
        lineTo((x + HALF_WIDTH) * unit, (y + SHOULDER) * unit)
        lineTo((x + HALF_WIDTH) * unit, (y + SHOULDER + SIDE) * unit)
        lineTo(x * unit, (y + 2 * SHOULDER + SIDE) * unit)
        lineTo((x - HALF_WIDTH) * unit, (y + SHOULDER + SIDE) * unit)
        lineTo((x - HALF_WIDTH) * unit, (y + SHOULDER) * unit)
        close()
    }
}
