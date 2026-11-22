package ch.ciuin.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.ciuin.app.ui.doodles.CrayonDoodle
import ch.ciuin.app.ui.doodles.Doodle
import ch.ciuin.app.ui.doodles.drawScribbleCircle
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinType
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every doodle on the ground and on a wool card, large and at the sizes the scenes use, and the
 * scribbled circle round a map pin: `build/design-shots/doodles-board.png`.
 */
class DoodlesBoardTest {
    @Test
    fun rendersEveryDoodle() {
        val file = DesignShots.screen("doodles-board", width = 1100, height = 560) {
            Column(Modifier.padding(40.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    for (doodle in Doodle.entries) {
                        Column(Modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CrayonDoodle(doodle, Modifier.size(96.dp))
                            Text(doodle.name, style = CiuinType.Attribution.copy(lineHeight = 14.sp), color = CiuinColors.SecondaryText)
                        }
                    }
                }
                Row(
                    Modifier.background(CiuinColors.Wool, RoundedCornerShape(28.dp)).padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (doodle in Doodle.entries) {
                        CrayonDoodle(doodle, Modifier.size(48.dp))
                        CrayonDoodle(doodle, Modifier.size(20.dp))
                    }
                }
                Canvas(Modifier.size(120.dp).background(CiuinColors.Tile)) {
                    drawScribbleCircle(center, 17.dp.toPx(), CiuinColors.Ink, 2.5.dp.toPx())
                    drawCircle(CiuinColors.Ground, 6.dp.toPx(), center)
                    drawCircle(CiuinColors.Calm, 3.5.dp.toPx(), center)
                }
            }
        }
        assertTrue(file.length() > 2_000, "${file.name} is empty")
    }
}
