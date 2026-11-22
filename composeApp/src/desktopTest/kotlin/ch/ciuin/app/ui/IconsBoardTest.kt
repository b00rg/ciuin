package ch.ciuin.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.ciuin.app.ui.icons.CiuinIcon
import ch.ciuin.app.ui.icons.CiuinIcons
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The grid of the "Icons" board in Paper, icon for icon: 32 dp icons in 112 dp cells, ten to a row.
 * `build/design-shots/icons-board.png` lines up with `00-icons.png` from 182 dp down.
 */
class IconsBoardTest {
    @Test
    fun hasAllThirtyFive() = assertEquals(35, CiuinIcons.all.size)

    @OptIn(ExperimentalLayoutApi::class)
    @Test
    fun rendersTheBoardGrid() {
        DesignShots.screen("icons-board", width = 1330, height = 554) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(start = 64.dp, end = 64.dp, top = 182.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                for ((name, icon) in CiuinIcons.all) {
                    Column(
                        modifier = Modifier.width(112.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CiuinIcon(icon, null, size = 32.dp)
                        Text(name, style = CiuinType.Attribution.copy(lineHeight = 14.sp), color = CiuinColors.SecondaryText)
                    }
                }
            }
        }
    }
}
