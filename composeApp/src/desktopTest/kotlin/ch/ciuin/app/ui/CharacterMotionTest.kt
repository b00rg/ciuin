package ch.ciuin.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.ciuin.app.ui.characters.AllSetScene
import ch.ciuin.app.ui.characters.Duck
import ch.ciuin.app.ui.characters.DuckMotion
import ch.ciuin.app.ui.characters.PondScene
import ch.ciuin.app.ui.characters.SharedScene
import ch.ciuin.app.ui.characters.Sheep
import ch.ciuin.app.ui.characters.SheepPose
import ch.ciuin.app.ui.theme.CiuinColors
import kotlin.test.Test

/**
 * Freezes each character animation at the keyframes the Motion board names, into
 * `build/design-shots/motion/`. The file name carries the time in milliseconds, so
 * `awake-4120.png` is the blink and `hop-0830.png` the top of the hop.
 */
class CharacterMotionTest {
    @Test
    fun rest() = frames("rest", 0, 2400, 3600) { Sheep(SheepPose.Rest, Modifier.size(164.dp, 150.dp)) }

    @Test
    fun awake() = frames("awake", 0, 1600, 4120, 6250, 6430) { Sheep(SheepPose.Awake, Modifier.size(164.dp)) }

    @Test
    fun earsUp() = frames("alert", 0, 120, 240, 280, 600) { Sheep(SheepPose.Alert, Modifier.size(164.dp), haptic = false) }

    @Test
    fun reading() = frames("reading", 0, 2500, 5400, 5600, 5800, 6200) { Sheep(SheepPose.Reading, Modifier.size(164.dp)) }

    @Test
    fun hop() = frames("hop", 0, 550, 830, 1180, 1300, 1700) { Sheep(SheepPose.Hop, Modifier.size(164.dp)) }

    @Test
    fun waddleIn() = frames("waddle", 0, 80, 240, 400, 640, 680, 760, 1000, width = 300) {
        Duck(Modifier.padding(start = 20.dp).size(90.dp), DuckMotion.WaddleIn)
    }

    @Test
    fun scenes() {
        for (millis in listOf(830, 2000)) {
            DesignShots.fitted("motion/scene-all-set-%04d".format(millis), atMillis = millis) {
                Box(Modifier.padding(24.dp)) { AllSetScene() }
            }
            DesignShots.fitted("motion/scene-shared-%04d".format(millis), atMillis = millis) {
                Box(Modifier.padding(24.dp)) { SharedScene() }
            }
        }
        for (millis in listOf(0, 900)) {
            DesignShots.fitted("motion/scene-pond-%04d".format(millis), atMillis = millis) {
                Box(Modifier.padding(24.dp)) { PondScene(ducks = 3) }
            }
        }
    }

    private fun frames(name: String, vararg millis: Int, width: Int = 240, content: @Composable () -> Unit) {
        for (time in millis) {
            DesignShots.screen("motion/$name-%04d".format(time), width = width, height = 220, atMillis = time) {
                Box(Modifier.fillMaxSize().background(CiuinColors.Wool), contentAlignment = Alignment.Center) { content() }
            }
        }
    }
}
