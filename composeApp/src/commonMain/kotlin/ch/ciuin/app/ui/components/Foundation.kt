package ch.ciuin.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinMotion
import ch.ciuin.app.ui.theme.LocalReduceMotion

/**
 * The press behaviour from the Motion board: the element scales to 0.97 and back in 90 ms. Only the
 * one primary button of a screen also gives a light haptic ([haptic]).
 *
 * With Reduce motion on, it dims instead of scaling. Elements shorter than 48 dp keep their drawn
 * size; Compose widens their touch area to 48 dp around them.
 */
internal fun Modifier.pressable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    haptic: Boolean = false,
    onClickLabel: String? = null,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduceMotion = LocalReduceMotion.current
    val amount by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(if (reduceMotion) CiuinMotion.REDUCED_FADE_MILLIS else CiuinMotion.PRESS_MILLIS),
    )
    val feedback = LocalHapticFeedback.current
    graphicsLayer {
        if (reduceMotion) {
            alpha = 1f - 0.2f * amount
        } else {
            val scale = 1f - (1f - CiuinMotion.PRESS_SCALE) * amount
            scaleX = scale
            scaleY = scale
        }
    }.clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        role = role,
        onClickLabel = onClickLabel,
    ) {
        if (haptic) feedback.performHapticFeedback(HapticFeedbackType.VirtualKey)
        onClick()
    }
}

/** A 1 dp line along the bottom edge, inside the element's own height, as a CSS bottom border is. */
internal fun Modifier.bottomHairline(color: Color = CiuinColors.Hairline): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    drawLine(color, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
}

/** A 1 dp line along the top edge, inside the element's own height. */
internal fun Modifier.topHairline(color: Color = CiuinColors.Hairline): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    drawLine(color, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
}

/** A hairline between list rows, for lists that are not built from the row components. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = CiuinColors.Hairline) {
    Box(modifier.fillMaxWidth().height(1.dp).drawBehind { drawRect(color) })
}

/** Text in one of the type styles, with the colour as a plain parameter. */
@Composable
internal fun Label(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = style.color,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        softWrap = maxLines != 1,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}
