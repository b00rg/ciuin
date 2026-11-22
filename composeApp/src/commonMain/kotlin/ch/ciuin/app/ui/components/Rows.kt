package ch.ciuin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import ch.ciuin.app.ui.icons.CiuinIcon
import ch.ciuin.app.ui.icons.CiuinIconStroke
import ch.ciuin.app.ui.icons.CiuinIcons
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinType

/**
 * The row every list in the design is built from: an optional leading slot in a fixed-width lane, a
 * column of text that takes the rest, an optional trailing slot, and a hairline underneath.
 *
 * The specific rows below fix the measurements for each list on the artboards; use this one only for
 * a list they do not cover.
 *
 * As on the artboards, a row with a fixed [height] contains its hairline, while a row sized by its
 * content ([verticalPadding], [minHeight]) grows by it. Pass `divider = false` for the last row.
 *
 * @param leadingLane width the leading slot is centred in, so glyphs and icons of different sizes
 * line up down the list (20 for level glyphs, 24 for icons, 40 for the permission icons).
 */
@Composable
fun ListRow(
    modifier: Modifier = Modifier,
    height: Dp = Dp.Unspecified,
    minHeight: Dp = Dp.Unspecified,
    verticalPadding: Dp = 0.dp,
    gap: Dp = 12.dp,
    divider: Boolean = true,
    dividerColor: Color = CiuinColors.Hairline,
    leadingLane: Dp = 20.dp,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sized = when {
        height.isSpecified -> Modifier.height(height)
        minHeight.isSpecified -> Modifier.heightIn(min = minHeight)
        else -> Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(sized)
            .then(if (divider) Modifier.bottomHairline(dividerColor).padding(bottom = 1.dp) else Modifier)
            .padding(vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        if (leading != null) Box(Modifier.width(leadingLane), contentAlignment = Alignment.Center) { leading() }
        Column(Modifier.weight(1f), content = content)
        if (trailing != null) trailing()
    }
}

private fun Modifier.rowClick(onClick: (() -> Unit)?, enabled: Boolean = true): Modifier =
    if (onClick == null) this else clickable(
        interactionSource = null,
        indication = null,
        enabled = enabled,
        role = Role.Button,
        onClick = onClick,
    )

/** How much a disabled row is faded, the same as a disabled button. */
private const val DISABLED_ROW_ALPHA = 0.4f

private fun Modifier.dimmedUnless(enabled: Boolean): Modifier =
    if (enabled) this else graphicsLayer { alpha = DISABLED_ROW_ALPHA }

/** A row's two lines: the title and, under it, a grey caption. */
@Composable
private fun TitleAndCaption(title: String, caption: String?, titleStyle: TextStyle = CiuinType.RowTitle, titleColor: Color = CiuinColors.Ink) {
    Label(title, titleStyle, color = titleColor)
    if (caption != null) Label(caption, CiuinType.Caption, color = CiuinColors.SecondaryText)
}

/**
 * The grey label above a group of rows: "Matches", "Recent", "Model size", "Listening today",
 * "Sent to Ciúin". 13/18 medium, with 4 dp under it when it sits directly on a list.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, bottomPadding: Dp = 4.dp) {
    Label(
        text = text,
        style = CiuinType.Eyebrow,
        modifier = modifier.semantics { heading() }.padding(bottom = bottomPadding),
        color = CiuinColors.SecondaryText,
    )
}

/**
 * A settings row that opens something: title, grey second line and a chevron in a 52 dp lane.
 * "Starting point" and "What is shared" on You. At least 60 high.
 */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
) {
    ListRow(
        modifier = modifier.rowClick(onClick),
        minHeight = 60.dp,
        divider = divider,
        trailing = {
            Box(Modifier.width(52.dp), contentAlignment = Alignment.CenterEnd) {
                CiuinIcon(CiuinIcons.ArrowRight01, null, size = 20.dp)
            }
        },
    ) { TitleAndCaption(title, subtitle) }
}

/**
 * A settings row with a switch: "Listen in the background", "Gentle alerts", "Reduce motion" on You.
 * The whole row toggles. At least 60 high. Use [subtitle] to say why the switch is disabled.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
    enabled: Boolean = true,
) {
    ListRow(
        modifier = modifier.toggleable(
            value = checked,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        minHeight = 60.dp,
        divider = divider,
        trailing = { CiuinSwitch(checked, onCheckedChange = null, enabled = enabled) },
    ) { TitleAndCaption(title, subtitle) }
}

/**
 * The last row of the You list, in the destructive red, with no line under it: "Reset what the
 * sheep learned". At least 56 high. Not [enabled] when there is nothing to reset: faded, no taps.
 */
@Composable
fun DestructiveRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    ListRow(modifier = modifier.rowClick(onClick, enabled).dimmedUnless(enabled), minHeight = 56.dp, divider = false) {
        Label(text, CiuinType.RowTitle, color = CiuinColors.Destructive)
    }
}

/**
 * A permission on the fourth onboarding screen: icon in a 40 dp lane, name and what it is for, and
 * either the outlined "Allow" button or the wool "Allowed" pill, both 92 by 40.
 */
@Composable
fun PermissionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    onAllow: () -> Unit,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
    allowLabel: String = "Allow",
    allowedLabel: String = "Allowed",
) {
    ListRow(
        modifier = modifier,
        verticalPadding = 12.dp,
        gap = 14.dp,
        divider = divider,
        dividerColor = CiuinColors.HairlineStrong,
        leadingLane = 40.dp,
        leading = { Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { CiuinIcon(icon, null) } },
        trailing = {
            if (granted) {
                AllowedPill(text = allowedLabel)
            } else {
                SecondaryButton(allowLabel, onAllow, Modifier.width(92.dp), SecondaryButtonSize.Compact)
            }
        },
    ) { TitleAndCaption(title, subtitle) }
}

/**
 * A place in the search results: level glyph in a 20 dp lane, name, a grey line of facts, and the
 * level word right-aligned in an 88 dp lane. 64 high.
 */
@Composable
fun PlaceResultRow(
    level: Level,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    levelWord: String = level.word,
    divider: Boolean = true,
) {
    ListRow(
        modifier = modifier.rowClick(onClick),
        height = 64.dp,
        divider = divider,
        leading = { LevelGlyph(level, lane = 20.dp) },
        trailing = {
            Label(
                text = levelWord,
                style = CiuinType.Label,
                modifier = Modifier.width(88.dp),
                color = if (level == Level.NoData) CiuinColors.SecondaryText else CiuinColors.Ink,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        },
    ) { TitleAndCaption(title, subtitle) }
}

/** A recent search: a clock and the place name. 56 high. */
@Composable
fun RecentRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, divider: Boolean = true) {
    ListRow(
        modifier = modifier.rowClick(onClick),
        height = 56.dp,
        divider = divider,
        leading = { CiuinIcon(CiuinIcons.Clock01, null, size = 20.dp) },
    ) { Label(text, CiuinType.BodyCompact) }
}

/**
 * One sense on the place sheet: glyph, its name in a 64 dp lane ("Sound"), the level word
 * ("Moderate") and a grey detail on the right ("58 dB measured"). 44 high.
 *
 * When there is no data, pass [actionLabel] to show an underlined link ("Add info") instead of the
 * detail.
 */
@Composable
fun DimensionRow(
    level: Level,
    name: String,
    levelWord: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    divider: Boolean = true,
) {
    ListRow(
        modifier = modifier,
        minHeight = 44.dp,
        verticalPadding = 4.dp,
        divider = divider,
        leading = { LevelGlyph(level, lane = 20.dp) },
        trailing = {
            if (actionLabel != null) {
                TextLinkButton(
                    text = actionLabel,
                    onClick = onAction,
                    modifier = Modifier.width(120.dp),
                    style = CiuinType.Eyebrow,
                    underline = true,
                    textAlign = TextAlign.End,
                )
            } else if (detail != null) {
                Label(
                    text = detail,
                    style = CiuinType.Caption,
                    modifier = Modifier.width(120.dp),
                    color = CiuinColors.SecondaryText,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(name, CiuinType.TileTitle, Modifier.width(64.dp), maxLines = 1)
            Label(
                text = levelWord,
                style = CiuinType.BodyMedium,
                modifier = Modifier.weight(1f),
                color = if (level == Level.NoData) CiuinColors.SecondaryText else CiuinColors.Ink,
                maxLines = 2,
            )
        }
    }
}

/**
 * One reading on the measure review: glyph, "Sound · Moderate" with a grey note, and the number
 * right-aligned in a 96 dp lane with an optional grey note under it ("57 dB", "peak 71").
 */
@Composable
fun ReadingRow(
    level: Level,
    title: String,
    subtitle: String,
    value: String,
    modifier: Modifier = Modifier,
    valueNote: String? = null,
    divider: Boolean = true,
) {
    ListRow(
        modifier = modifier,
        verticalPadding = 12.dp,
        divider = divider,
        leading = { LevelGlyph(level, lane = 20.dp) },
        trailing = {
            Column(Modifier.width(96.dp), horizontalAlignment = Alignment.End) {
                Label(value, CiuinType.Subtitle, maxLines = 1)
                if (valueNote != null) Label(valueNote, CiuinType.Caption, color = CiuinColors.SecondaryText, maxLines = 1)
            }
        },
    ) { TitleAndCaption(title, subtitle) }
}

/**
 * One instruction of a walking route: icon in a 24 dp lane, the instruction, and the distance
 * right-aligned in a 48 dp lane. 48 high. The step you are on is [current] and set in medium weight.
 */
@Composable
fun RouteStepRow(
    icon: ImageVector,
    text: String,
    distance: String,
    modifier: Modifier = Modifier,
    current: Boolean = false,
    iconSize: Dp = 24.dp,
    divider: Boolean = true,
) {
    ListRow(
        modifier = modifier,
        height = 48.dp,
        divider = divider,
        leadingLane = 24.dp,
        leading = { CiuinIcon(icon, null, size = iconSize) },
        trailing = {
            Label(
                text = distance,
                style = CiuinType.Caption,
                modifier = Modifier.width(48.dp),
                color = CiuinColors.SecondaryText,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        },
    ) { Label(text, if (current) CiuinType.TileTitle else CiuinType.BodyMedium) }
}

/**
 * One choice in a plain list of options: title, grey second line and a [RadioMark]. The model sizes
 * "Small", "Medium" and "Large" on the model screen. At least 56 high. An [RadioState.Unavailable] row is
 * not clickable and its title is grey.
 */
@Composable
fun RadioRow(
    title: String,
    subtitle: String,
    state: RadioState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
) {
    ListRow(
        modifier = modifier.selectable(
            selected = state == RadioState.Selected,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = state != RadioState.Unavailable,
            role = Role.RadioButton,
            onClick = onClick,
        ),
        minHeight = 56.dp,
        verticalPadding = 4.dp,
        divider = divider,
        trailing = { RadioMark(state) },
    ) {
        TitleAndCaption(
            title = title,
            caption = subtitle,
            titleColor = if (state == RadioState.Unavailable) CiuinColors.Muted else CiuinColors.Ink,
        )
    }
}

/**
 * One line of the privacy lists: a green tick for what is sent ("Average and peak loudness") or a
 * lock for what stays on the phone ("Audio and camera frames"). At least 40 high, icon in a 24 dp lane.
 */
@Composable
fun SharedFactRow(text: String, shared: Boolean, modifier: Modifier = Modifier, divider: Boolean = true) {
    ListRow(
        modifier = modifier,
        minHeight = 40.dp,
        verticalPadding = 4.dp,
        divider = divider,
        leadingLane = 24.dp,
        leading = {
            if (shared) {
                CiuinIcon(CiuinIcons.Tick02, "Sent", size = 20.dp, tint = CiuinColors.Calm, strokeWidth = CiuinIconStroke.Check)
            } else {
                CiuinIcon(CiuinIcons.SquareLock02, "Stays on this phone", size = 20.dp)
            }
        },
    ) { Label(text, CiuinType.BodyCompact) }
}

/**
 * A closing line under a hairline, with a text link at its right: "Learned from 23 of your answers" and a
 * red "Reset" at the bottom of the model screen. 52 high.
 */
@Composable
fun LinkRow(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    actionColor: Color = CiuinColors.Destructive,
    actionEnabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).topHairline().padding(top = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Label(text, CiuinType.RowTitle, Modifier.weight(1f))
        TextLinkButton(
            actionLabel,
            onAction,
            color = actionColor,
            style = CiuinType.ButtonMedium,
            textAlign = TextAlign.End,
            enabled = actionEnabled,
        )
    }
}

/**
 * A step in a short ordered list: a black 32 dp disc with the number and one line of text. The
 * three tips on the last onboarding screen. Space the steps 14 dp apart.
 */
@Composable
fun NumberedStep(number: Int, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(32.dp).background(CiuinColors.Ink, CircleShape), contentAlignment = Alignment.Center) {
            Label(number.toString(), CiuinType.StepNumber, color = CiuinColors.OnInk)
        }
        Label(text, CiuinType.BodyCompact, Modifier.weight(1f))
    }
}

/**
 * A ticked line in a short checklist: "Hold the phone steady", "Aim the camera at the room" in the
 * how-to card on the measure start screen. Space the lines 6 dp apart.
 */
@Composable
fun ChecklistItem(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CiuinIcon(CiuinIcons.Tick02, null, size = 16.dp, strokeWidth = CiuinIconStroke.Check)
        Label(text, CiuinType.BodySmall)
    }
}

/**
 * A small grey note with an icon in front: "Audio and video stay on this phone." with a lock under
 * the measure ring (18 dp icon, 14/20 text), "Most of the time no model is running." with a battery
 * on the model screen (22 dp icon, 13/18 text, 10 dp gap).
 */
@Composable
fun IconNote(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
    gap: Dp = 8.dp,
    style: TextStyle = CiuinType.BodySmall,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
        CiuinIcon(icon, null, size = iconSize)
        Label(text, style, color = CiuinColors.SecondaryText)
    }
}
