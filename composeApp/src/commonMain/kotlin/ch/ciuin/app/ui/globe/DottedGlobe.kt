package ch.ciuin.app.ui.globe

import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.atan2
import kotlin.math.acos
import kotlin.math.PI
import ch.ciuin.app.ui.theme.LocalReduceMotion
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
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
    // Sheep paths: a few sheep amble along great-circle paths between places, like flight routes but slow
    // (one crossing takes ~40 s). Still under reduce motion.
    val reduceMotion = LocalReduceMotion.current
    val walk = if (reduceMotion) null else rememberInfiniteTransition(label = "sheep paths").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SHEEP_WALK_MS, easing = LinearEasing)),
        label = "walk",
    )
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
                drawSheepPaths(projection, radius, walk?.value ?: 0.5f)
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


/** One crossing of a sheep path, end to end. Slow on purpose: a glance should see sheep, not traffic. */
private const val SHEEP_WALK_MS = 40_000

/** Long paths the sheep walk across the side of the globe the app opens on (start, end, phase offset). */
private val SheepPaths = listOf(
    Triple(LAUSANNE_CENTRE, GlobePoint(64.15, -21.94), 0.00f),          // to Reykjavik
    Triple(GlobePoint(30.04, 31.24), LAUSANNE_CENTRE, 0.30f),           // from Cairo
    Triple(GlobePoint(38.72, -9.14), GlobePoint(60.17, 24.94), 0.55f),  // Lisbon to Helsinki
    Triple(GlobePoint(53.35, -6.26), GlobePoint(41.01, 28.98), 0.80f),  // Dublin, where the name comes from, to Istanbul
)

/** A dotted trail along each path (the part on the near side), and a small sheep walking it. */
private fun DrawScope.drawSheepPaths(projection: GlobeProjection, radius: Float, time: Float) {
    val trail = CiuinColors.Ink.copy(alpha = 0.28f)
    for ((from, to, phase) in SheepPaths) {
        // Trail: a small dot about every 2 degrees of arc, where that point faces us.
        val steps = (arcDegrees(from, to) / 2.0).toInt().coerceAtLeast(4)
        for (i in 0..steps) {
            val p = projection.project(slerp(from, to, i.toDouble() / steps))
            if (!p.visible) continue
            drawCircle(trail, radius = 1.1.dp.toPx(), center = center + Offset(p.x.toFloat() * radius, -p.y.toFloat() * radius))
        }
        val t = ((time + phase) % 1f).toDouble()
        val here = projection.project(slerp(from, to, t))
        val ahead = projection.project(slerp(from, to, (t + 0.01).coerceAtMost(1.0)))
        if (!here.visible) continue
        val at = center + Offset(here.x.toFloat() * radius, -here.y.toFloat() * radius)
        val next = center + Offset(ahead.x.toFloat() * radius, -ahead.y.toFloat() * radius)
        drawTinySheep(at, facingLeft = next.x < at.x)
    }
}

/** A sheep about 14 dp long: a woolly body of overlapping puffs, a slate head and two legs. */
private fun DrawScope.drawTinySheep(at: Offset, facingLeft: Boolean) {
    val u = 0.75.dp.toPx()
    val dir = if (facingLeft) -1f else 1f
    val ink = CiuinColors.Ink
    // Legs first, under the wool.
    drawLine(ink, at + Offset(-3f * u, 2f * u), at + Offset(-3f * u, 6f * u), strokeWidth = 1.3f * u, cap = StrokeCap.Round)
    drawLine(ink, at + Offset(3f * u, 2f * u), at + Offset(3f * u, 6f * u), strokeWidth = 1.3f * u, cap = StrokeCap.Round)
    val puffs = listOf(Offset(-4f, 0f), Offset(0f, -2f), Offset(4f, 0f), Offset(0f, 1.5f), Offset(-2f, -1.5f), Offset(2f, -1.5f))
    for (p in puffs) drawCircle(ink, radius = 3.6f * u, center = at + p * u)
    for (p in puffs) drawCircle(CiuinColors.Ground, radius = 2.8f * u, center = at + p * u)
    // Head at the front, a touch higher than the body.
    drawOval(ink, topLeft = at + Offset(dir * 6.5f * u - 2.4f * u, -4.4f * u), size = Size(4.8f * u, 4f * u))
}

/** Angle between two points on the globe, in degrees. */
private fun arcDegrees(a: GlobePoint, b: GlobePoint): Double {
    val la1 = a.lat * PI / 180
    val la2 = b.lat * PI / 180
    val dLon = (b.lon - a.lon) * PI / 180
    return acos((sin(la1) * sin(la2) + cos(la1) * cos(la2) * cos(dLon)).coerceIn(-1.0, 1.0)) * 180 / PI
}

/** The point a fraction [t] of the way along the great circle from [a] to [b]. */
private fun slerp(a: GlobePoint, b: GlobePoint, t: Double): GlobePoint {
    fun vec(p: GlobePoint): Triple<Double, Double, Double> {
        val la = p.lat * PI / 180
        val lo = p.lon * PI / 180
        return Triple(cos(la) * cos(lo), cos(la) * sin(lo), sin(la))
    }
    val (ax, ay, az) = vec(a)
    val (bx, by, bz) = vec(b)
    val dot = (ax * bx + ay * by + az * bz).coerceIn(-1.0, 1.0)
    val omega = acos(dot)
    if (omega < 1e-9) return a
    val s = sin(omega)
    val wa = sin((1 - t) * omega) / s
    val wb = sin(t * omega) / s
    val x = wa * ax + wb * bx
    val y = wa * ay + wb * by
    val z = wa * az + wb * bz
    return GlobePoint(atan2(z, sqrt(x * x + y * y)) * 180 / PI, atan2(y, x) * 180 / PI)
}
