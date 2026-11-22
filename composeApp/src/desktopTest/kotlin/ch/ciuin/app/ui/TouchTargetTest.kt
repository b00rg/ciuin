package ch.ciuin.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import ch.ciuin.app.ui.components.BannerButton
import ch.ciuin.app.ui.components.CiuinSwitch
import ch.ciuin.app.ui.components.LayerChip
import ch.ciuin.app.ui.components.Level
import ch.ciuin.app.ui.components.MapPin
import ch.ciuin.app.ui.components.RoundIconButton
import ch.ciuin.app.ui.components.SecondaryButton
import ch.ciuin.app.ui.components.SecondaryButtonSize
import ch.ciuin.app.ui.components.SegmentedControl
import ch.ciuin.app.ui.icons.CiuinIcons
import ch.ciuin.app.ui.theme.CiuinTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Some controls are drawn shorter than 48 dp (a 40 dp chip, a 32 dp switch). Compose still gives
 * them a 48 dp touch target. This taps just outside each one's drawn edge, inside that target, and
 * checks that the tap lands.
 */
class TouchTargetTest {
    @Test
    fun layerChip() = assertTapAboveLands(40) { LayerChip("Sound", false, it) }

    @Test
    fun compactButton() = assertTapAboveLands(40) { SecondaryButton("Allow", it, size = SecondaryButtonSize.Compact) }

    @Test
    fun smallButton() = assertTapAboveLands(44) { SecondaryButton("Too much", it, size = SecondaryButtonSize.Small) }

    @Test
    fun closeButton() = assertTapAboveLands(44) { RoundIconButton(CiuinIcons.Cancel01, "Close", it) }

    @Test
    fun bannerButton() = assertTapAboveLands(40) { BannerButton("Retry", it) }

    @Test
    fun mapPin() = assertTapAboveLands(36) { MapPin(Level.Calm, "Library", it) }

    @Test
    fun switch() = assertTapAboveLands(32) { onClick -> CiuinSwitch(false, { onClick() }) }

    @Test
    fun segment() = assertTapAboveLands(40) { onClick -> SegmentedControl(listOf("Day", "Night"), 1, { onClick() }) }

    private fun assertTapAboveLands(drawnHeight: Int, content: @Composable (onClick: () -> Unit) -> Unit) {
        var clicked = false
        var bounds = Rect.Zero
        ImageComposeScene(width = 400, height = 300, density = Density(1f)) {
            CiuinTheme(reduceMotion = true) {
                Box(Modifier.padding(60.dp)) {
                    Box(Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }) { content { clicked = true } }
                }
            }
        }.use { scene ->
            scene.render(0)
            assertTrue(bounds.height <= drawnHeight + 1f, "drawn ${bounds.height} dp tall, expected $drawnHeight")
            // Halfway into the invisible margin that makes the target 48 dp tall.
            val margin = (48f - bounds.height) / 2f
            val tap = Offset(bounds.left + 20f, bounds.top - margin / 2f)
            scene.sendPointerEvent(PointerEventType.Press, tap, type = PointerType.Touch, timeMillis = 10)
            scene.render(16_000_000)
            scene.sendPointerEvent(PointerEventType.Release, tap, type = PointerType.Touch, timeMillis = 60)
            scene.render(80_000_000)
        }
        assertTrue(clicked, "a tap ${"%.1f".format((48f - bounds.height) / 4f)} dp above the control did not reach it")
    }
}
