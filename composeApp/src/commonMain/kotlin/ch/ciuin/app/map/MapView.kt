package ch.ciuin.app.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/** One pin on the map. [color] is decided by the caller (e.g. from the user's p_overwhelm). */
data class MapMarker(val id: String, val lat: Double, val lon: Double, val label: String, val color: Color)

/** Heatmap square in normalised Web Mercator coords (see [MapState]): top-left [x], [y], side [size]. */
data class HeatRect(val x: Double, val y: Double, val size: Double, val color: Color)

private const val HEAT_ALPHA = 0.5f

private const val TILE_PX = 256
private const val MAX_TILE_ZOOM = 19
private val BACKGROUND = Color(0xFFE8E4DC)

/**
 * Slippy map drawn on a Canvas: raster tiles + circle markers.
 * Drag / pinch to move and zoom, mouse wheel and double-tap to zoom, tap a marker to select it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MapView(
    state: MapState,
    markers: List<MapMarker>,
    selectedId: String?,
    onMarkerTap: (MapMarker?) -> Unit,
    modifier: Modifier = Modifier,
    heat: List<HeatRect> = emptyList(),
    tileUrl: String = TileCache.OSM_TILES,
) {
    val scope = rememberCoroutineScope()
    val cache = remember(tileUrl) { TileCache(scope, tileUrl) }
    DisposableEffect(cache) { onDispose { cache.close() } }

    val density = LocalDensity.current
    // Draw tiles at 256 dp so they stay readable on high-density phone screens.
    val tilePx = TILE_PX * density.density
    val hitRadiusPx = with(density) { 22.dp.toPx() }
    val textMeasurer = rememberTextMeasurer()
    val currentMarkers by rememberUpdatedState(markers)
    val currentOnTap by rememberUpdatedState(onMarkerTap)

    Canvas(
        modifier
            .pointerInput(state, tilePx) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    state.pan(pan, tilePx)
                    if (zoom != 1f) state.zoomAt(zoom.toDouble(), centroid, size.toSize(), tilePx)
                }
            }
            .pointerInput(state, tilePx) {
                detectTapGestures(
                    onDoubleTap = { pos -> state.zoomAt(2.0, pos, size.toSize(), tilePx) },
                    onTap = { pos ->
                        val viewport = size.toSize()
                        val hit = currentMarkers
                            .map { it to (state.toScreen(it.lat, it.lon, viewport, tilePx) - pos).getDistance() }
                            .filter { it.second <= hitRadiusPx }
                            .minByOrNull { it.second }
                        currentOnTap(hit?.first)
                    },
                )
            }
            .pointerInput(state, tilePx) {
                // Mouse wheel zoom (desktop). Touch devices never produce Scroll events.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val change = event.changes.first()
                            val factor = 2.0.pow(-change.scrollDelta.y * 0.5)
                            state.zoomAt(factor, change.position, size.toSize(), tilePx)
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            },
    ) {
        drawRect(BACKGROUND)
        drawTiles(state, cache, tilePx)
        if (heat.isNotEmpty()) {
            val w = state.worldSize(tilePx)
            for (h in heat) {
                val left = (size.width / 2.0 + (h.x - state.centerX) * w).toFloat()
                val top = (size.height / 2.0 + (h.y - state.centerY) * w).toFloat()
                val side = (h.size * w).toFloat()
                if (left > size.width || top > size.height || left + side < 0 || top + side < 0) continue
                drawRect(h.color, Offset(left, top), Size(side, side), alpha = HEAT_ALPHA)
            }
        }

        val labelsVisible = state.zoom >= 15.0
        for (m in markers) {
            val p = state.toScreen(m.lat, m.lon, size, tilePx)
            if (p.x < -50 || p.y < -50 || p.x > size.width + 50 || p.y > size.height + 50) continue
            val selected = m.id == selectedId
            val r = (if (selected) 12.dp else 8.dp).toPx()
            drawCircle(Color.Black.copy(alpha = 0.25f), r + 2.dp.toPx(), p + Offset(0f, 1.5.dp.toPx()))
            drawCircle(m.color, r, p)
            drawCircle(Color.White, r, p, style = Stroke(width = (if (selected) 3.dp else 2.dp).toPx()))
            if (labelsVisible || selected) {
                val layout = textMeasurer.measure(
                    m.label,
                    TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1B1B1B)),
                )
                val topLeft = Offset(p.x - layout.size.width / 2f, p.y + r + 3.dp.toPx())
                drawRect(
                    Color.White.copy(alpha = 0.8f),
                    topLeft - Offset(3.dp.toPx(), 1.dp.toPx()),
                    Size(layout.size.width + 6.dp.toPx(), layout.size.height + 2.dp.toPx()),
                )
                drawText(layout, topLeft = topLeft)
            }
        }
    }
}

private fun DrawScope.drawTiles(state: MapState, cache: TileCache, tilePx: Float) {
    val zoom = state.zoom
    val tz = floor(zoom).toInt().coerceIn(0, MAX_TILE_ZOOM)
    val n = 1 shl tz
    val ts = tilePx * 2.0.pow(zoom - tz) // on-screen size of one tile at zoom tz
    val world = ts * n
    val originX = size.width / 2.0 - state.centerX * world // screen x of world x = 0
    val originY = size.height / 2.0 - state.centerY * world

    val x0 = floor(-originX / ts).toInt()
    val x1 = floor((size.width - originX) / ts).toInt()
    val y0 = floor(-originY / ts).toInt().coerceAtLeast(0)
    val y1 = floor((size.height - originY) / ts).toInt().coerceAtMost(n - 1)

    for (ty in y0..y1) {
        for (tx in x0..x1) {
            val wx = ((tx % n) + n) % n
            // Integer edges from the same formula for neighbours, so tiles never leave seams.
            val left = (originX + tx * ts).roundToInt()
            val top = (originY + ty * ts).roundToInt()
            val dstOffset = IntOffset(left, top)
            val dstSize = IntSize((originX + (tx + 1) * ts).roundToInt() - left, (originY + (ty + 1) * ts).roundToInt() - top)

            val img = cache.get(TileKey(tz, wx, ty))
            if (img != null) {
                drawImage(img, dstOffset = dstOffset, dstSize = dstSize)
                continue
            }
            // While loading, stretch the matching quarter of the parent tile if we have it.
            if (tz > 0) {
                val parent = cache.peek(TileKey(tz - 1, wx / 2, ty / 2)) ?: continue
                val half = parent.width / 2
                drawImage(
                    parent,
                    srcOffset = IntOffset((wx % 2) * half, (ty % 2) * half),
                    srcSize = IntSize(half, half),
                    dstOffset = dstOffset,
                    dstSize = dstSize,
                )
            }
        }
    }
}
