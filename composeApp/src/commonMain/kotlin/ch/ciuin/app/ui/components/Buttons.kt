package ch.ciuin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.ciuin.app.ui.icons.CiuinIcon
import ch.ciuin.app.ui.icons.CiuinIconStroke
import ch.ciuin.app.ui.icons.CiuinIcons
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinElevation
import ch.ciuin.app.ui.theme.CiuinShadow
import ch.ciuin.app.ui.theme.CiuinType
import ch.ciuin.app.ui.theme.ciuinShadow

/**
 * The one black button of a screen: 56 high, fully rounded. "Get started", "Continue", "Start
 * measuring", "Share measurement", "Take me there" (with [leadingIcon] `Navigation03`).
 *
 * It hugs its label with 28 dp of side padding; give it `Modifier.fillMaxWidth()` or a weight to
 * stretch it. Pressing scales it to 0.97 with a light haptic, the only button that has one.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .pressable(onClick, enabled = enabled, haptic = true)
            .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA }
            .height(56.dp)
            .background(CiuinColors.Ink, CircleShape)
            .pillPadding(28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (leadingIcon != null) CiuinIcon(leadingIcon, null, size = 22.dp, tint = CiuinColors.OnInk)
        Label(text, CiuinType.Button, color = CiuinColors.OnInk, maxLines = 1)
    }
}

/** The four heights the outlined button comes in. */
enum class SecondaryButtonSize(internal val height: Dp, internal val padding: Dp) {
    /** 56 high, 16/20 label: beside or under a primary button ("Measure here", "Cancel", "Directions"). */
    Large(56.dp, 28.dp),

    /** 52 high, 15/18 label: a pair of choices under a primary button ("I'm okay here", "Snooze 15 min", "End route"). */
    Medium(52.dp, 20.dp),

    /** 44 high, 14/18 label: inline in a sheet row ("Too much", "This is fine" on the place sheet). */
    Small(44.dp, 16.dp),

    /** 40 high, 14/18 label: the "Allow" button in a permission row, which is 92 wide. */
    Compact(40.dp, 14.dp),
}

/**
 * The outlined button: white with a 1.5 dp ink border. Secondary actions next to or below the
 * primary one, in the four sizes of [SecondaryButtonSize]. It hugs its label unless given a width.
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: SecondaryButtonSize = SecondaryButtonSize.Large,
    enabled: Boolean = true,
) {
    OutlinedPill(text, onClick, modifier, size, enabled, CiuinColors.Ink)
}

/**
 * The outlined button for an action that deletes something: the same shape with the border and label
 * in the destructive red. "Reset" in the reset confirmation sheet.
 */
@Composable
fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: SecondaryButtonSize = SecondaryButtonSize.Large,
    enabled: Boolean = true,
) {
    OutlinedPill(text, onClick, modifier, size, enabled, CiuinColors.Destructive)
}

@Composable
private fun OutlinedPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    size: SecondaryButtonSize,
    enabled: Boolean,
    color: Color,
) {
    val style = when (size) {
        SecondaryButtonSize.Large -> CiuinType.Button
        SecondaryButtonSize.Medium -> CiuinType.ButtonMedium
        SecondaryButtonSize.Small, SecondaryButtonSize.Compact -> CiuinType.Chip
    }
    Box(
        modifier = modifier
            .pressable(onClick, enabled = enabled)
            .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA }
            .height(size.height)
            .background(CiuinColors.Ground, CircleShape)
            .border(1.5.dp, color, CircleShape)
            .pillPadding(size.padding + 1.5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, style, color = color, maxLines = 1)
    }
}

/**
 * A button that is only its words: 48 high, at least 48 wide. "Skip" (grey) and "Cancel" in a top
 * row, "Discard" and "Measure another place" under a primary button, "Reset" (destructive red, in
 * [CiuinType.ButtonMedium]).
 *
 * With [underline], it is the inline link of the Foundations board ("Not now") and of the place
 * sheet ("Add info", in [CiuinType.Eyebrow] weight 500).
 */
@Composable
fun TextLinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = CiuinColors.Ink,
    style: TextStyle = CiuinType.Button,
    underline: Boolean = false,
    textAlign: TextAlign = TextAlign.Center,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .pressable(onClick, enabled = enabled)
            .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA }
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
        contentAlignment = when (textAlign) {
            TextAlign.Right, TextAlign.End -> Alignment.CenterEnd
            TextAlign.Left, TextAlign.Start -> Alignment.CenterStart
            else -> Alignment.Center
        },
    ) {
        Label(
            text = text,
            style = if (underline) style.copy(textDecoration = TextDecoration.Underline) else style,
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * One of two answers to "How did it feel to you?" on the measure review: 48 high, outlined. The
 * chosen one fills with wool, thickens its border to 2 dp and gains a tick.
 */
@Composable
fun ChoiceButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (selected) 2.dp else 1.5.dp
    Row(
        modifier = modifier
            .semantics {
                this.selected = selected
                stateDescription = if (selected) "Selected" else "Not selected"
            }
            .pressable(onClick)
            .height(48.dp)
            .background(if (selected) CiuinColors.Wool else CiuinColors.Ground, CircleShape)
            .border(border, CiuinColors.Ink, CircleShape)
            .pillPadding(16.dp + border),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (selected) CiuinIcon(CiuinIcons.Tick02, null, size = 18.dp, strokeWidth = CiuinIconStroke.Selected)
        Label(text, CiuinType.ButtonMedium, maxLines = 1)
    }
}

/** How a round icon button is drawn. */
enum class RoundIconButtonStyle {
    /** 44, grey tile disc: "Close" on the place sheet. */
    Tile,

    /** 44, white disc: "Close" on the black walking header. */
    OnInk,

    /** 52, white with a hairline border and a soft shadow, floating over the map: "Locate me". */
    Floating,

    /** A 48 touch area with no disc and the icon against its left edge: the back arrow. */
    Plain,
}

/**
 * A round button holding one icon. It always needs a [contentDescription], since there is no word
 * beside it. See [RoundIconButtonStyle] for where each look is used.
 */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: RoundIconButtonStyle = RoundIconButtonStyle.Tile,
    enabled: Boolean = true,
) {
    val compact = style == RoundIconButtonStyle.Tile || style == RoundIconButtonStyle.OnInk
    val surface = when (style) {
        RoundIconButtonStyle.Tile -> Modifier.size(44.dp).background(CiuinColors.Tile, CircleShape)
        RoundIconButtonStyle.OnInk -> Modifier.size(44.dp).background(CiuinColors.Ground, CircleShape)
        RoundIconButtonStyle.Floating -> Modifier.size(52.dp).floatingDisc(CiuinElevation.Floating)
        RoundIconButtonStyle.Plain -> Modifier.size(48.dp)
    }
    Box(
        modifier = modifier.pressable(onClick, enabled = enabled).then(surface),
        contentAlignment = if (style == RoundIconButtonStyle.Plain) Alignment.CenterStart else Alignment.Center,
    ) {
        CiuinIcon(icon, contentDescription, size = if (compact) 20.dp else 24.dp, tint = CiuinColors.Ink)
    }
}

/**
 * The round microphone button beside the search field on the map: 52, floating. A green dot at its
 * top right shows that background listening is on.
 */
@Composable
fun ListeningButton(
    listening: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = if (listening) "Listening is on" else "Listening is off",
) {
    Box(
        modifier = modifier.pressable(onClick).size(52.dp).floatingDisc(CiuinElevation.Search),
        contentAlignment = Alignment.Center,
    ) {
        CiuinIcon(CiuinIcons.Mic01, contentDescription, tint = CiuinColors.Ink)
        if (listening) {
            // A 12 dp dot with a 2 dp white ring, 2 dp inside the disc's edge at the top right.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .size(12.dp)
                    .background(CiuinColors.Ground, CircleShape)
                    .padding(2.dp)
                    .background(CiuinColors.Calm, CircleShape),
            )
        }
    }
}

/** A white disc with a hairline edge and a soft shadow. The edge is painted under the content, not over it. */
private fun Modifier.floatingDisc(shadow: CiuinShadow): Modifier = this
    .ciuinShadow(shadow, CircleShape)
    .background(CiuinColors.FloatingOutline, CircleShape)
    .padding(1.dp)
    .background(CiuinColors.Ground, CircleShape)

/**
 * The small white pill button inside a black banner: 40 high, 14/18 label. "Retry" in the offline
 * banner.
 */
@Composable
fun BannerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .pressable(onClick)
            .height(40.dp)
            .background(CiuinColors.Ground, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, CiuinType.Chip, maxLines = 1)
    }
}

/**
 * The state a permission shows once it is granted: a wool pill the size of the "Allow" button, with
 * a green tick. Pair it with `SecondaryButton(size = Compact, modifier = Modifier.width(92.dp))`.
 */
@Composable
fun AllowedPill(modifier: Modifier = Modifier, text: String = "Allowed") {
    Row(
        modifier = modifier.width(92.dp).height(40.dp).background(CiuinColors.Wool, RoundedCornerShape(20.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        CiuinIcon(CiuinIcons.Tick02, null, size = 16.dp, tint = CiuinColors.Calm, strokeWidth = CiuinIconStroke.CheckBold)
        Label(text, CiuinType.Chip, maxLines = 1)
    }
}

/**
 * Side padding for a pill-shaped button: [preferred] when the button hugs its label, shrinking to
 * [minimum] when the button is given less room (two buttons sharing a row), before the label is cut.
 */
private fun Modifier.pillPadding(preferred: Dp, minimum: Dp = 12.dp): Modifier = layout { measurable, constraints ->
    val room = if (constraints.hasBoundedWidth) {
        (constraints.maxWidth - 2 * minimum.roundToPx()).coerceAtLeast(0)
    } else {
        Constraints.Infinity
    }
    val content = measurable.measure(constraints.copy(minWidth = 0, maxWidth = room))
    val width = (content.width + 2 * preferred.roundToPx()).coerceIn(constraints.minWidth, constraints.maxWidth)
    val height = content.height.coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(width, height) { content.place((width - content.width) / 2, (height - content.height) / 2) }
}

private const val DISABLED_ALPHA = 0.4f
