package ch.ciuin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinType

/**
 * One layer of the map: "For you", "Sound", "Light", "Crowd", "Access". 40 high; the selected chip
 * is black with white text, the others white with a hairline border.
 *
 * The side padding is 16 as on the map screens (the Foundations board draws the same chip with 18).
 */
@Composable
fun LayerChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .semantics { this.selected = selected }
            .pressable(onClick, role = Role.Tab)
            .height(40.dp)
            .then(
                if (selected) {
                    Modifier.background(CiuinColors.Ink, shape).padding(horizontal = horizontalPadding)
                } else {
                    Modifier
                        .background(CiuinColors.Ground, shape)
                        .border(1.dp, CiuinColors.Outline, shape)
                        .padding(horizontal = horizontalPadding + 1.dp)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, CiuinType.Chip, color = if (selected) CiuinColors.OnInk else CiuinColors.Ink, maxLines = 1)
    }
}

/**
 * The row of layer chips under the search field on every map screen: 8 dp apart, scrolling sideways
 * and running off the right edge. [contentPadding] is the 16 dp map margin on the left.
 */
@Composable
fun LayerChipRow(
    layers: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        layers.forEachIndexed { index, layer ->
            LayerChip(layer, selected = index == selectedIndex, onClick = { onSelect(index) })
        }
    }
}

/**
 * A fact about a place, not a control: "Quiet room", "Outdoor seating", "No music" on the place
 * sheet. 32 high on the grey tile colour.
 */
@Composable
fun TagChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(32.dp)
            .background(CiuinColors.Tile, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, CiuinType.Pill, maxLines = 1)
    }
}

/** How a [LabelPill] is drawn. */
enum class LabelPillStyle {
    /** Black with white text, 32 high: the city label on the globe ("Lausanne"). */
    Ink,

    /** Black with white text, 28 high: "New" next to the duck that just joined. */
    InkSmall,

    /** White with a hairline border, 32 high: the destination label on a route ("Library"). */
    Outlined,
}

/** A short word in a pill that labels something in a picture or on the map. See [LabelPillStyle]. */
@Composable
fun LabelPill(text: String, modifier: Modifier = Modifier, style: LabelPillStyle = LabelPillStyle.Ink) {
    val height = if (style == LabelPillStyle.InkSmall) 28.dp else 32.dp
    val surface = if (style == LabelPillStyle.Outlined) {
        Modifier
            .background(CiuinColors.Ground, CircleShape)
            .border(1.dp, CiuinColors.FloatingOutline, CircleShape)
            .padding(horizontal = 13.dp)
    } else {
        Modifier.background(CiuinColors.Ink, CircleShape).padding(horizontal = 12.dp)
    }
    Box(modifier.height(height).then(surface), contentAlignment = Alignment.Center) {
        Label(
            text = text,
            style = CiuinType.Pill,
            color = if (style == LabelPillStyle.Outlined) CiuinColors.Ink else CiuinColors.OnInk,
            maxLines = 1,
        )
    }
}

/**
 * The white pill that states a level over a picture: glyph and words, 36 high. "Too much · 74 dB"
 * at the top left of the alert's sheep tile.
 */
@Composable
fun LevelPill(level: Level, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(36.dp)
            .background(CiuinColors.Ground, RoundedCornerShape(18.dp))
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LevelGlyph(level, size = LevelGlyphSize.Small)
        Label(text, CiuinType.Label, maxLines = 1)
    }
}

/**
 * The grey pill with a green dot at the top right of the globe screen: "Live". 36 high. It says
 * the counts below are current; it is not a control.
 */
@Composable
fun LivePill(modifier: Modifier = Modifier, text: String = "Live") {
    Row(
        modifier = modifier
            .height(36.dp)
            .background(CiuinColors.Tile, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).background(CiuinColors.Calm, CircleShape))
        Label(text, CiuinType.Eyebrow, color = CiuinColors.Ink, maxLines = 1)
    }
}
