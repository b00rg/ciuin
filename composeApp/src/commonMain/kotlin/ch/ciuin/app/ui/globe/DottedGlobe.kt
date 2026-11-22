package ch.ciuin.app.ui.globe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import ch.ciuin.app.globe.GlobePoint
import ch.ciuin.app.globe.GlobeProjection
import ch.ciuin.app.globe.GlobeSpike
import ch.ciuin.app.globe.LAUSANNE_CENTRE
import ch.ciuin.app.globe.globeDots
import ch.ciuin.app.ui.components.LabelPill
import ch.ciuin.app.ui.theme.CiuinColors
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val LandDots by lazy { globeDots(stepDeg = 4.0) }
private const val DiscRadius = 150f / 324f

@Composable
internal fun DottedGlobe(
    centre: GlobePoint,
    spikes: List<GlobeSpike>,
    onRotate: (GlobePoint) -> Unit,
    rotationEnabled: Boolean,
) {
    val projection = remember(centre) { GlobeProjection(centre) }
    val currentCentre = rememberUpdatedState(centre)
    val rotate = rememberUpdatedState(onRotate)
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val diameter = maxWidth.coerceAtMost(324.dp)
        BoxWithConstraints(
            Modifier.widthIn(max = diameter).fillMaxWidth().aspectRatio(1f)
                .semantics { contentDescription = "Globe showing where people have measured" },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize().pointerInput(rotationEnabled) {
                if (!rotationEnabled) return@pointerInput
                var draggedCentre = currentCentre.value
                detectDragGestures(
                    onDragStart = { draggedCentre = currentCentre.value },
                    onDrag = { change, amount ->
                        change.consume()
                        val radius = size.width * DiscRadius
                        if (radius > 0) {
                            draggedCentre = GlobeProjection(draggedCentre).drag(
                                deltaX = (amount.x / radius).toDouble(),
                                deltaY = (-amount.y / radius).toDouble(),
                            )
                            rotate.value(draggedCentre)
                        }
                    },
                )
            }) {
                val radius = size.minDimension * DiscRadius
                drawCircle(CiuinColors.Tile, radius)
                for (dot in LandDots) {
                    val position = projection.project(dot)
                    if (!position.visible) continue
                    val x = position.x.toFloat()
                    val y = position.y.toFloat()
                    val depth = sqrt((1f - x * x - y * y).coerceAtLeast(0f))
                    drawCircle(CiuinColors.Ink, (0.65f + 1.25f * depth).dp.toPx(), center + Offset(x * radius, -y * radius))
                }
                for (spike in spikes) {
                    val position = projection.project(spike.point)
                    if (!position.visible) continue
                    val radial = Offset(position.x.toFloat(), -position.y.toFloat())
                    val foot = center + radial * radius
                    val tip = center + radial * (radius + 30.dp.toPx() * spike.height.toFloat())
                    drawLine(CiuinColors.Hoof, foot, tip, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(CiuinColors.Hoof, radius = 1.8.dp.toPx(), center = tip)
                }
                val lausanne = projection.project(LAUSANNE_CENTRE)
                if (lausanne.visible) {
                    val mark = center + Offset(lausanne.x.toFloat() * radius, -lausanne.y.toFloat() * radius)
                    drawCircle(CiuinColors.Hoof, radius = 5.dp.toPx(), center = mark)
                }
            }
            val city = projection.project(LAUSANNE_CENTRE)
            if (city.visible) {
                val radius = maxWidth * DiscRadius
                val labelSide = if (city.x <= 0.30) 1 else -1
                LabelPill("Lausanne", Modifier.absoluteOffset {
                    IntOffset(
                        (radius.toPx() * city.x + 60.dp.toPx() * labelSide).roundToInt(),
                        (-radius.toPx() * city.y - 22.dp.toPx()).roundToInt(),
                    )
                })
            }
        }
    }
}
