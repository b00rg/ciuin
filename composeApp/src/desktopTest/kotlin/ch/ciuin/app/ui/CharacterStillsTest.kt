package ch.ciuin.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.ciuin.app.ui.characters.Duck
import ch.ciuin.app.ui.characters.Sheep
import ch.ciuin.app.ui.characters.SheepPose
import ch.ciuin.app.ui.theme.CiuinColors
import kotlin.test.Test

/**
 * Each character alone on wool, at the size and sub-pixel position it has on the Foundations board,
 * so the PNGs in `build/design-shots/characters/` can be laid over crops of `00-foundations.png`
 * (a 1x export, hence density 1).
 * Poses are rendered with Reduce motion on, which is the board's still drawing.
 */
class CharacterStillsTest {
    @Test
    fun rendersEveryPose() {
        still("rest", 160f, 146f, 0.594f) { Sheep(SheepPose.Rest, it, zzz = false) }
        still("awake", 146f, 146f, 0.797f) { Sheep(SheepPose.Awake, it) }
        still("alert", 146f, 146f, 0.992f) { Sheep(SheepPose.Alert, it, haptic = false) }
        still("reading", 146f, 146f, 0.195f) { Sheep(SheepPose.Reading, it) }
        still("duck", 120f, 120f, 0.398f) { Duck(it) }
    }

    /** [fraction] is the fractional x of the drawing on the board, so the anti-aliasing lines up too. */
    private fun still(name: String, width: Float, height: Float, fraction: Float, content: @Composable (Modifier) -> Unit) {
        DesignShots.screen("characters/$name", width.toInt() + 2, height.toInt() + 2, reduceMotion = true, density = 1f) {
            Box(Modifier.fillMaxSize().background(CiuinColors.Wool)) {
                content(Modifier.absoluteOffset((1f + fraction).dp, 1.dp).size(width.dp, height.dp))
            }
        }
    }
}
