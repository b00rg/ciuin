package ch.ciuin.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ch.ciuin.app.ui.icons.CiuinIcon
import ch.ciuin.app.ui.icons.CiuinIconStroke
import ch.ciuin.app.ui.icons.CiuinIcons
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinElevation
import ch.ciuin.app.ui.theme.CiuinMotion
import ch.ciuin.app.ui.theme.CiuinType
import ch.ciuin.app.ui.theme.LocalReduceMotion
import ch.ciuin.app.ui.theme.ciuinShadow

/**
 * The toggle on the You screen ("Listen in the background", "Gentle alerts", "Reduce motion"):
 * a 52 by 32 track, black when on and grey when off, with a 26 dp white thumb.
 *
 * Inside a row, leave [onCheckedChange] null and make the row toggleable (as `SettingsSwitchRow`
 * does), so the whole row is one target.
 */
@Composable
fun CiuinSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val reduceMotion = LocalReduceMotion.current
    val spec = tween<Float>(if (reduceMotion) CiuinMotion.REDUCED_FADE_MILLIS else 160, easing = CiuinMotion.Ease)
    val position by animateFloatAsState(if (checked) 1f else 0f, spec)
    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .then(toggle)
            .size(width = 52.dp, height = 32.dp)
            .drawBehind {
                val track = lerp(CiuinColors.Disabled, CiuinColors.Ink, position)
                drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
                val inset = 3.dp.toPx()
                val radius = 13.dp.toPx()
                // Under Reduce motion the thumb does not slide: it fades between its two places.
                val travel = size.width - 2 * inset - 2 * radius
                if (reduceMotion) {
                    drawCircle(CiuinColors.Ground.copy(alpha = 1f - position), radius, center.copy(x = inset + radius))
                    drawCircle(CiuinColors.Ground.copy(alpha = position), radius, center.copy(x = inset + radius + travel))
                } else {
                    drawCircle(CiuinColors.Ground, radius, center.copy(x = inset + radius + travel * position))
                }
            },
    )
}

/**
 * The two-way switch between Day and Night readings at the top right of the Sound sheet. A wool
 * track with a white pill under the chosen side; 40 high in all, each side 34.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.background(CiuinColors.Wool, RoundedCornerShape(20.dp)).padding(3.dp)) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .semantics { this.selected = selected }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    )
                    .height(34.dp)
                    .then(if (selected) Modifier.background(CiuinColors.Ground, RoundedCornerShape(17.dp)) else Modifier)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Label(
                    text = option,
                    style = CiuinType.Pill,
                    color = if (selected) CiuinColors.Ink else CiuinColors.SecondaryText,
                    maxLines = 1,
                )
            }
        }
    }
}

/** What a [RadioMark] shows. */
enum class RadioState { Selected, Unselected, Unavailable }

/**
 * The 24 dp mark at the right of anything you pick one of: a black disc with a white tick when
 * chosen, a grey ring when not, a dashed ring when the option cannot be chosen yet ("Large" model).
 * It is decoration; the card or row around it is the control.
 */
@Composable
fun RadioMark(state: RadioState, modifier: Modifier = Modifier) {
    when (state) {
        RadioState.Selected -> Box(
            modifier.size(24.dp).background(CiuinColors.Ink, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            CiuinIcon(CiuinIcons.Tick02, null, size = 16.dp, tint = CiuinColors.OnInk, strokeWidth = CiuinIconStroke.CheckBold)
        }
        RadioState.Unselected -> Box(modifier.size(24.dp).border(1.5.dp, CiuinColors.Muted, CircleShape))
        RadioState.Unavailable -> Box(modifier.size(24.dp).drawBehind { drawDashedRing(CiuinColors.Disabled) })
    }
}

/**
 * Progress through the five onboarding screens: a 24 dp bar for the current one, black dots for
 * those done and grey dots for those to come, 6 dp apart.
 */
@Composable
fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = "Step ${current + 1} of $count" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val width by animateDpAsState(
                targetValue = if (index == current) 24.dp else 6.dp,
                animationSpec = tween(if (reduceMotion) 0 else CiuinMotion.LAYER_SWITCH_MILLIS, easing = CiuinMotion.Ease),
            )
            Box(
                Modifier
                    .width(width)
                    .height(6.dp)
                    .background(if (index <= current) CiuinColors.Ink else CiuinColors.Handle, CircleShape),
            )
        }
    }
}

/**
 * The search field at rest, on top of the map: a white pill, 52 high, with a hairline border, a soft
 * shadow, the search icon and grey placeholder words. It is a button; tapping it opens the search
 * screen, where [SearchField] takes over.
 */
@Composable
fun SearchBar(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search a place",
) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier = modifier
            .pressable(onClick)
            .ciuinShadow(CiuinElevation.Search, shape)
            .height(52.dp)
            .background(CiuinColors.Ground, shape)
            .border(1.dp, CiuinColors.FloatingOutline, shape)
            .padding(horizontal = 19.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CiuinIcon(CiuinIcons.Search01, null, size = 22.dp, tint = CiuinColors.Ink)
        Label(placeholder, CiuinType.Body, color = CiuinColors.SecondaryText, maxLines = 1)
    }
}

/**
 * The search field while typing, on the search screen: the same pill with a 2 dp black border, no
 * shadow, black text and a black caret. Put a "Cancel" `TextLinkButton` 12 dp to its right.
 *
 * Pass a [focusRequester] and request focus when the screen opens.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search a place",
    focusRequester: FocusRequester = remember { FocusRequester() },
    onSearch: () -> Unit = {},
) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier = modifier
            .height(52.dp)
            .background(CiuinColors.Ground, shape)
            .border(2.dp, CiuinColors.Ink, shape)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CiuinIcon(CiuinIcons.Search01, null, size = 22.dp, tint = CiuinColors.Ink)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).focusRequester(focusRequester).semantics { contentDescription = placeholder },
            textStyle = CiuinType.Body,
            singleLine = true,
            cursorBrush = SolidColor(CiuinColors.Ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            decorationBox = { field ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Label(placeholder, CiuinType.Body, color = CiuinColors.SecondaryText, maxLines = 1)
                    field()
                }
            },
        )
    }
}

/**
 * The dot that marks where you are on the map: black with a white ring and a soft shadow, inside a
 * 56 dp translucent halo.
 */
@Composable
fun YouAreHereDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(56.dp)
            .background(CiuinColors.LocationHalo, CircleShape)
            .clearAndSetSemantics { contentDescription = "You are here" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .ciuinShadow(CiuinElevation.LocationDot, CircleShape)
                .size(20.dp)
                .background(CiuinColors.Ground, CircleShape)
                .padding(3.dp)
                .background(CiuinColors.Ink, CircleShape),
        )
    }
}
