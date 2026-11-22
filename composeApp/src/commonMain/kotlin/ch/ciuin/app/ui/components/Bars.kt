package ch.ciuin.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import ch.ciuin.app.ui.theme.CiuinMotion
import ch.ciuin.app.ui.theme.LocalReduceMotion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinType
import kotlin.math.roundToInt

/**
 * The bar itself: 8 high, ink on a light grey track, both fully rounded. [fraction] is 0 to 1; the
 * fill is rounded to a whole dp as on the artboards. When [fraction] changes (Day to Night on the
 * Sound sheet) the fill eases to its new length; it does not grow in when first shown, and under
 * reduce motion it jumps.
 */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val target = fraction.coerceIn(0f, 1f)
    val clamped by animateFloatAsState(
        target,
        if (LocalReduceMotion.current) snap() else tween(CiuinMotion.SWITCH_MILLIS, easing = CiuinMotion.EaseInOut),
        label = "bar fill",
    )
    Box(
        modifier
            .height(8.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f) }
            .drawBehind {
                val corner = CornerRadius(size.height / 2)
                drawRoundRect(CiuinColors.Hairline, cornerRadius = corner)
                val unit = 1.dp.toPx()
                val fill = ((size.width * clamped) / unit).roundToInt() * unit
                if (fill > 0f) drawRoundRect(CiuinColors.Ink, size = Size(fill, size.height), cornerRadius = corner)
            },
    )
}

/**
 * A labelled bar with its value: the label in a fixed lane on the left, the bar taking the rest,
 * the value right-aligned in a fixed lane.
 *
 * - "Where it comes from" on the Sound sheet: `labelWidth = 48`, `valueWidth = 48`, `gap = 12`.
 * - Wheelchair access on the Access sheet: a [leading] level glyph in a 16 dp lane, `labelWidth = 112`,
 *   `valueWidth = 40`, `gap = 10`.
 * - "Listening today" on the model screen: `labelWidth = 150`, `valueWidth = 40`, `gap = 10`.
 */
@Composable
fun BarRow(
    label: String,
    fraction: Float,
    value: String,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 112.dp,
    valueWidth: Dp = 40.dp,
    gap: Dp = 10.dp,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$label, $value" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        if (leading != null) leading()
        Label(label, CiuinType.BodySmall, Modifier.width(labelWidth), maxLines = 1)
        ProgressBar(fraction, Modifier.weight(1f))
        Label(value, CiuinType.Label, Modifier.width(valueWidth), textAlign = TextAlign.End, maxLines = 1)
    }
}

/**
 * The ring around the reading sheep while a measurement runs: an 8 dp black arc that fills clockwise
 * from the top over a light grey track, 264 across, with a 216 dp wool disc inside for [content].
 *
 * [progress] is 0 to 1. During capture the ring uses the same clock as the reading and fills
 * clockwise at a constant rate over 30 seconds. The sheep in the middle is 156 dp.
 */
@Composable
fun MeasureRing(
    progress: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 264.dp,
    content: @Composable BoxScope.() -> Unit,
) = MeasureRing(progress, { progress }, modifier, diameter, content)

/** [drawProgress] can advance between changes to the accessible [progress] value. */
@Composable
internal fun MeasureRing(
    progress: Float,
    drawProgress: () -> Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 264.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val clamped = progress.coerceIn(0f, 1f)
    val scale = diameter / 264.dp
    Box(
        modifier = modifier
            .size(diameter)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f) }
            .drawBehind {
                val drawn = drawProgress().coerceIn(0f, 1f)
                val stroke = 8.dp.toPx() * scale
                val inset = stroke / 2 + 4.dp.toPx() * scale
                val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
                drawArc(CiuinColors.RingTrack, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                if (drawn > 0f) {
                    drawArc(
                        color = CiuinColors.Ink,
                        startAngle = -90f,
                        sweepAngle = 360f * drawn,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(216.dp * scale).drawBehind { drawCircle(CiuinColors.Wool) },
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}
