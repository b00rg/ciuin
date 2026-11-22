package ch.ciuin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.ciuin.app.ui.doodles.CrayonDoodle
import ch.ciuin.app.ui.doodles.Doodle
import ch.ciuin.app.ui.icons.CiuinIcon
import ch.ciuin.app.ui.icons.CiuinIcons
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinRadii
import ch.ciuin.app.ui.theme.CiuinType

/**
 * One of the options you pick from on a screen: a white card with a hairline border that becomes a
 * 2 dp black border when chosen, and a [RadioMark] on the right.
 *
 * With a [leadingIcon] it is a preset on "What bothers you most?" (16 dp padding, icon on a 48 dp
 * wool disc, 16/24 title). Without one it is a place on "Pick your place" (14 dp vertical padding,
 * 16/22 title, 13/18 second line). Stack cards 12 and 10 dp apart respectively.
 *
 * With `enabled = false` the card dims to 40% and ignores taps (while a choice is being saved).
 */
@Composable
fun SelectableCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(CiuinRadii.Option)
    val border = if (selected) 2.dp else 1.dp
    val vertical = if (leadingIcon != null) 16.dp else 14.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .background(CiuinColors.Ground, shape)
            .border(border, if (selected) CiuinColors.Ink else CiuinColors.Outline, shape)
            .padding(horizontal = 16.dp + border, vertical = vertical + border),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (leadingIcon != null) IconDisc(leadingIcon, size = 48.dp, iconSize = 24.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (leadingIcon != null) {
                Label(title, CiuinType.OptionTitle)
                Label(subtitle, CiuinType.BodySmall, color = CiuinColors.SecondaryText)
            } else {
                Label(title, CiuinType.Subtitle)
                Label(subtitle, CiuinType.Caption, color = CiuinColors.SecondaryText)
            }
        }
        RadioMark(if (selected) RadioState.Selected else RadioState.Unselected)
    }
}

/**
 * An icon on a round disc. Wool, 48 with a 24 dp icon in a preset card; wool, 56 with a 28 dp icon
 * at the head of the Access sheet (26 for the bin in the reset sheet); white, 44 with a 24 dp icon in
 * the model card.
 */
@Composable
fun IconDisc(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    iconSize: Dp = 28.dp,
    color: Color = CiuinColors.Wool,
    contentDescription: String? = null,
) {
    Box(modifier.size(size).background(color, CircleShape), contentAlignment = Alignment.Center) {
        CiuinIcon(icon, contentDescription, size = iconSize)
    }
}

/**
 * The wool square a character sits in beside a sheet's verdict: 72 with 24 dp corners for the sheep
 * on the map and route sheets, 84 with 28 dp corners for the empty state of a layer with no data.
 *
 * Empty states pass a [doodle], drawn small in the top corner behind the character.
 */
@Composable
fun CharacterTile(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    cornerRadius: Dp = CiuinRadii.Card,
    doodle: Doodle? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.size(size).background(CiuinColors.Wool, RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.Center,
    ) {
        if (doodle != null) {
            CrayonDoodle(doodle, Modifier.align(Alignment.TopEnd).padding(top = size / 12, end = size / 12).size(size / 4), rotation = 10f)
        }
        content()
    }
}

/**
 * The title block of a sheet or card: an optional grey eyebrow, a title and an optional grey line,
 * 2 dp apart. "Around you · Flon / Calm for you / 46 dB · dim light · few people".
 *
 * Defaults are the map sheets' (22/28 title, 14/20 line). The place sheet uses
 * [CiuinType.PlaceTitle]; wool cards use [CiuinType.CardTitle] or [CiuinType.Subtitle] with a
 * [CiuinType.Caption] line.
 */
@Composable
fun TitleBlock(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null,
    titleStyle: TextStyle = CiuinType.SheetTitle,
    subtitleStyle: TextStyle = CiuinType.BodySmall,
    gap: Dp = 2.dp,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        if (eyebrow != null) Label(eyebrow, CiuinType.Eyebrow, color = CiuinColors.SecondaryText)
        Label(title, titleStyle, Modifier.semantics { heading() })
        if (subtitle != null) Label(subtitle, subtitleStyle, color = CiuinColors.SecondaryText)
    }
}

/** The measurements of each wool card in the design. */
enum class WoolCardStyle(
    internal val radius: Dp,
    internal val padding: PaddingValues,
    internal val gap: Dp,
) {
    /** "Only numbers are shared" on onboarding 4: 96 by 88 resting sheep, 16/22 title, 14/20 text 4 dp under it. */
    Privacy(28.dp, PaddingValues(horizontal = 20.dp, vertical = 16.dp), 16.dp),

    /** "Likely fine for you" on the place sheet: 60 dp awake sheep, 18/24 title, 13/18 line. */
    Verdict(22.dp, PaddingValues(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), 14.dp),

    /** The how-to on measure start: 72 dp reading sheep and a checklist. */
    HowTo(24.dp, PaddingValues(start = 12.dp, end = 16.dp, top = 14.dp, bottom = 14.dp), 14.dp),

    /** "Share it and a duck joins the pond" on the measure review: 64 dp duck, 16/22 title, 13/18 text. */
    Reward(24.dp, PaddingValues(start = 14.dp, end = 18.dp, top = 16.dp, bottom = 16.dp), 14.dp),

    /** "The server rejects anything that is not on the first list." on privacy: 72 by 66 resting sheep, 14/20 ink text. */
    Note(24.dp, PaddingValues(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), 14.dp),
}

/**
 * A wool-coloured card with a character on the left and words on the right. The sheep or duck says
 * something here; the card is never a button. Pick the [style] of the artboard you are building.
 */
@Composable
fun WoolCard(
    style: WoolCardStyle,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CiuinColors.Wool, RoundedCornerShape(style.radius))
            .padding(style.padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(style.gap),
        content = content,
    )
}

/**
 * A wool card whose content is stacked rather than side by side: the model card on the model
 * screen (16 dp padding, 14 dp between its header and its row of white [StatTile]s).
 */
@Composable
fun WoolPanel(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    gap: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CiuinColors.Wool, RoundedCornerShape(CiuinRadii.Card))
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** How a [StatTile] is drawn. */
enum class StatTileStyle {
    /** Grey tile, number over its unit: "2,873 places" on the globe. */
    ValueFirst,

    /** Grey tile, grey label over the number: the live "Sound 57 dB" readouts while measuring. */
    LabelFirst,

    /** White tile inside a wool panel, smaller number over its label: "1.17 MB Size" on the model screen. */
    Inner,
}

/**
 * A number with the word that says what it counts. Put three in a row with `Modifier.weight(1f)`,
 * 10 dp apart (8 for [StatTileStyle.Inner]).
 */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    style: StatTileStyle = StatTileStyle.ValueFirst,
) {
    val inner = style == StatTileStyle.Inner
    Column(
        modifier = modifier
            .background(
                color = if (inner) CiuinColors.Ground else CiuinColors.Tile,
                shape = RoundedCornerShape(if (inner) CiuinRadii.TileInner else CiuinRadii.Tile),
            )
            .padding(horizontal = if (inner) 12.dp else 14.dp, vertical = if (inner) 10.dp else 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(if (style == StatTileStyle.LabelFirst) 2.dp else 0.dp),
    ) {
        when (style) {
            StatTileStyle.ValueFirst -> {
                Label(value, CiuinType.StatValue, maxLines = 1)
                Label(label, CiuinType.Caption, color = CiuinColors.SecondaryText, maxLines = 1)
            }
            StatTileStyle.LabelFirst -> {
                Label(label, CiuinType.Eyebrow, color = CiuinColors.SecondaryText, maxLines = 1)
                Label(value, CiuinType.StatValue, maxLines = 1)
            }
            StatTileStyle.Inner -> {
                Label(value, CiuinType.CardTitle, maxLines = 1)
                Label(label, CiuinType.Caption, color = CiuinColors.SecondaryText, maxLines = 1)
            }
        }
    }
}

/**
 * A grey tile that carries one fact, with something in a 24 dp lane in front of it and, when it
 * leads somewhere, a chevron: "Nearest quiet spot: Library" (a calm glyph, tappable) on the map
 * sheet; "2 measurements waiting" (a clock, not tappable) on the offline sheet.
 */
@Composable
fun InfoTile(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    leading: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(onClick) else Modifier)
            .background(CiuinColors.Tile, RoundedCornerShape(CiuinRadii.Tile))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { leading() }
        Column(Modifier.weight(1f)) {
            Label(title, CiuinType.TileTitle)
            Label(subtitle, CiuinType.Caption, color = CiuinColors.SecondaryText)
        }
        if (onClick != null) CiuinIcon(CiuinIcons.ArrowRight01, null, size = 20.dp)
    }
}

/**
 * A grey tile with one centred line: the "57 dB · 82 lux · 3 people" summary after sharing a
 * measurement. 52 high.
 */
@Composable
fun SummaryTile(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().height(52.dp).background(CiuinColors.Tile, RoundedCornerShape(CiuinRadii.Tile)),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, CiuinType.TileTitle, maxLines = 1)
    }
}

/**
 * The card on the alert that offers a calm place: a map preview on top (116 high; draw the route in
 * [preview]) and under it the place with its level glyph, a grey line of facts and its reading.
 * White with a hairline border and 24 dp corners.
 */
@Composable
fun QuietPlaceCard(
    level: Level,
    title: String,
    subtitle: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    preview: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(CiuinRadii.Card)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(onClick) else Modifier)
            .clip(shape)
            .background(CiuinColors.Ground)
            .border(1.dp, CiuinColors.FloatingOutline, shape)
            .padding(1.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(116.dp).background(CiuinColors.MapGround), content = preview)
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LevelGlyph(level, lane = 20.dp)
            Column(Modifier.weight(1f)) {
                Label(title, CiuinType.Subtitle)
                Label(subtitle, CiuinType.Caption, color = CiuinColors.SecondaryText)
            }
            Label(value, CiuinType.Subtitle, maxLines = 1)
        }
    }
}

/**
 * A big reading with its level word on the same baseline: "60 dB Moderate" at the head of the Sound
 * sheet.
 */
@Composable
fun BigReading(value: String, word: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(value, CiuinType.ScreenTitle, Modifier.alignByBaseline(), maxLines = 1)
        Label(word, CiuinType.RowTitle, Modifier.alignByBaseline(), maxLines = 1)
    }
}
