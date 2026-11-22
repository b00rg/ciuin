package ch.ciuin.app.map

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import ch.ciuin.app.route.RoutePoint
import ch.ciuin.app.ui.components.Level
import ch.ciuin.app.ui.doodles.drawScribbleCircle
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinMotion
import ch.ciuin.app.ui.theme.CiuinType
import kotlin.math.floor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.roundToInt

internal data class MapMarker(
    val id: String,
    val lat: Double,
    val lon: Double,
    val label: String,
    val level: Level,
    val showLabel: Boolean = false,
)

internal data class HeatHex(
    val x: Double,
    val y: Double,
    val radius: Double,
    val color: Color,
    val outline: Boolean = false,
)

private data class MarkerHit(val marker: MapMarker, val point: Offset, val label: Rect?)

/** Hexagons fill their whole cell (a seamless honeycomb) and overlap by a hair to hide anti-aliasing seams. */
private const val HEX_FILL = 1.0f
private const val HEX_OVERLAP_PX = 0.5f
private const val LABELS_ZOOM = 18.0

/** Mean radius of the crayon circle round the selected place: clear of its 6 dp dot. */
private val SELECTED_SCRIBBLE_RADIUS = 17.dp
private const val TILE_PX = 256
private const val MAX_TILE_ZOOM = 19
private val lightTiles = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.2f) })

/**
 * The same faded tiles turned over for the dark palette: white land becomes the slate map ground and
 * dark labels and outlines become a soft cream, so roads read as light lines on dark land.
 */
private val darkTiles = ColorFilter.colorMatrix(
    ColorMatrix().apply {
        setToSaturation(0.2f)
        val faded = values.copyOf()
        // Per channel: out = top - (top - land) * faded(in) / 255, so 255 maps to land and 0 to top.
        val land = floatArrayOf(35f, 38f, 47f)
        val top = floatArrayOf(196f, 198f, 184f)
        for (row in 0..2) {
            val k = (top[row] - land[row]) / 255f
            for (col in 0..3) values[row * 5 + col] = -k * faded[row * 5 + col]
            values[row * 5 + 4] = top[row]
        }
    },
)

private val tileColor: ColorFilter get() = if (CiuinColors.dark) darkTiles else lightTiles

@Composable
internal fun rememberMapTileCache(tileUrl: String = TileCache.OSM_TILES): TileCache {
    val scope = rememberCoroutineScope()
    val cache = remember(tileUrl) { TileCache(scope, tileUrl) }
    DisposableEffect(cache) { onDispose { cache.close() } }
    return cache
}

/**
 * Slippy map drawn on a Canvas: raster tiles + circle markers.
 * Drag / pinch to move and zoom, mouse wheel and double-tap to zoom, tap a marker to select it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun MapView(
    state: MapState,
    markers: List<MapMarker>,
    selectedId: String?,
    onMarkerTap: (MapMarker?) -> Unit,
    modifier: Modifier = Modifier,
    heat: List<HeatHex> = emptyList(),
    tileUrl: String = TileCache.OSM_TILES,
    routeOverlay: MapRouteOverlay? = null,
    userLocation: RoutePoint? = null,
    layerKey: Any? = null,
    reduceMotion: Boolean = false,
    interactive: Boolean = true,
    tileCache: TileCache? = null,
) {
    val cache = tileCache ?: rememberMapTileCache(tileUrl)

    val density = LocalDensity.current
    // Draw tiles at 256 dp so they stay readable on high-density phone screens.
    val tilePx = TILE_PX * density.density
    val dotRadiusPx = with(density) { 6.dp.toPx() }
    val dotHitRadiusPx = with(density) { 22.dp.toPx() }
    val textMeasurer = rememberTextMeasurer()
    val currentOnTap by rememberUpdatedState(onMarkerTap)
    val visibleMarkers = remember { mutableListOf<MarkerHit>() }
    val targetLayer by rememberUpdatedState(layerKey)
    val targetHeat by rememberUpdatedState(heat)

    val gestures = if (!interactive) Modifier else Modifier
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
                        val nearestDot = visibleMarkers.asSequence()
                            .map { it to (it.point - pos).getDistance() }
                            .minByOrNull { it.second }
                        val hit = nearestDot?.takeIf { it.second <= dotRadiusPx }?.first
                            ?: visibleMarkers.firstOrNull { it.label?.contains(pos) == true }
                            ?: nearestDot?.takeIf { it.second <= dotHitRadiusPx }?.first
                        currentOnTap(hit?.marker)
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
            }
    Box(modifier.then(gestures)) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(CiuinColors.MapGround)
            drawTiles(state, cache, tilePx)
            drawRect(CiuinColors.MapGround.copy(alpha = 0.52f))
        }

        Crossfade(
            targetState = layerKey,
            modifier = Modifier.matchParentSize(),
            animationSpec = if (reduceMotion) CiuinMotion.reducedFade() else CiuinMotion.layerSwitch(),
            label = "map layer",
        ) { shownLayer ->
            val snapshot = remember { mutableStateOf(targetHeat) }
            if (shownLayer == targetLayer) {
                val latest = targetHeat
                SideEffect { snapshot.value = latest }
            }
            Canvas(Modifier.fillMaxSize()) { drawHeat(state, tilePx, snapshot.value) }
        }

        Canvas(Modifier.matchParentSize()) {
            if (routeOverlay != null) drawQuietPlaceRoute(routeOverlay, state, tilePx)
            else userLocation?.let { drawHere(state.toScreen(it.lat, it.lon, size, tilePx)) }

            visibleMarkers.clear()
            val markerLabelStyle = CiuinType.Caption
            val labelsVisible = state.zoom >= LABELS_ZOOM
            val drawn = markers.sortedByDescending { it.id == selectedId }
            for (marker in drawn) {
                val point = state.toScreen(marker.lat, marker.lon, size, tilePx)
                if (point.x !in 0f..size.width || point.y !in 0f..size.height) continue
                val selected = marker.id == selectedId
                // Scribbled round the selected place, under its label so the label reads on top.
                if (selected) drawScribbleCircle(point, SELECTED_SCRIBBLE_RADIUS.toPx(), CiuinColors.Ink, 2.5.dp.toPx())
                var labelBounds: Rect? = null
                if (selected || marker.showLabel || labelsVisible) {
                    val label = textMeasurer.measure(
                        marker.label,
                        markerLabelStyle.copy(color = if (selected) CiuinColors.OnInk else CiuinColors.Ink),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        constraints = androidx.compose.ui.unit.Constraints(maxWidth = 140.dp.roundToPx()),
                    )
                    val height = 32.dp.toPx()
                    val side = 8.dp.toPx()
                    val width = side * 4 + label.size.width
                    val offset = Offset(
                        (point.x - width / 2).coerceIn(0f, (size.width - width).coerceAtLeast(0f)),
                        point.y - height - 5.dp.toPx(),
                    )
                    val box = Rect(offset, Size(width, height))
                    if (selected || visibleMarkers.none { it.label?.overlaps(box) == true }) {
                        labelBounds = box
                        drawRoundRect(
                            if (selected) CiuinColors.Ink else CiuinColors.Ground,
                            topLeft = offset, size = box.size, cornerRadius = CornerRadius(height / 2),
                        )
                        val glyph = Offset(offset.x + side * 1.5f, offset.y + height / 2)
                        drawCircle(marker.level.tint, 9.dp.toPx(), glyph)
                        drawLevel(marker.level, glyph, 5.dp.toPx())
                        drawText(label, topLeft = Offset(offset.x + side * 3, offset.y + (height - label.size.height) / 2))
                    }
                }
                drawCircle(CiuinColors.Ground, dotRadiusPx, point)
                drawLevel(marker.level, point, 3.5.dp.toPx())
                visibleMarkers += MarkerHit(marker, point, labelBounds)
            }
        }
    }
}

private fun DrawScope.drawHeat(state: MapState, tilePx: Float, heat: List<HeatHex>) {
    if (heat.isEmpty()) return
    val world = state.worldSize(tilePx)
    val path = Path()
    val outline = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.dp.toPx())))

    fun hexPath(hex: HeatHex, grow: Float): Boolean {
        val dx = shortestWrappedDelta(hex.x - state.centerX)
        val cx = (size.width / 2.0 + dx * world).toFloat()
        val cy = (size.height / 2.0 + (hex.y - state.centerY) * world).toFloat()
        val radius = (hex.radius * world).toFloat()
        if (cx < -radius || cy < -radius || cx > size.width + radius || cy > size.height + radius) return false
        path.reset()
        val r = radius * HEX_FILL + grow
        for (i in 0 until 6) {
            val angle = (PI / 180 * (60 * i - 30)).toFloat()
            val px = cx + r * cos(angle)
            val py = cy + r * sin(angle)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        return true
    }

    // Filled hexagons: each transparency (one per level) goes into its own layer. Inside a layer the hexagons are
    // drawn opaque and overlap by a hair, so neighbours meet with no gap and no darker seam; the layer then
    // applies the level's transparency once.
    val bounds = Rect(Offset.Zero, size)
    heat.filter { !it.outline }.groupBy { it.color.alpha }.forEach { (alpha, group) ->
        drawIntoCanvas { canvas ->
            canvas.saveLayer(bounds, Paint().apply { this.alpha = alpha })
            for (hex in group) if (hexPath(hex, HEX_OVERLAP_PX)) drawPath(path, hex.color.copy(alpha = 1f))
            canvas.restore()
        }
    }
    for (hex in heat) if (hex.outline && hexPath(hex, 0f)) drawPath(path, hex.color, style = outline)
}

private fun DrawScope.drawHere(point: Offset) {
    drawCircle(CiuinColors.LocationHalo, 15.dp.toPx(), point)
    drawCircle(CiuinColors.Ground, 8.dp.toPx(), point)
    drawCircle(CiuinColors.Ink, 5.5.dp.toPx(), point)
}

private fun DrawScope.drawLevel(level: Level, center: Offset, radius: Float) {
    val rect = Rect(center, radius)
    when (level) {
        Level.Calm -> drawCircle(level.color, radius, center)
        Level.Moderate -> rotate(45f, center) {
            drawRoundRect(level.color, rect.topLeft, rect.size, CornerRadius(1.dp.toPx()))
        }
        Level.Loud -> drawRoundRect(level.color, rect.topLeft, rect.size, CornerRadius(1.dp.toPx()))
        Level.NoData -> drawCircle(level.color, radius, center,
            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))))
    }
}

private fun DrawScope.drawQuietPlaceRoute(route: MapRouteOverlay, state: MapState, tilePx: Float) {
    val world = state.worldSize(tilePx)
    val origin = Offset(
        (size.width / 2.0 + shortestWrappedDelta(route.origin.x - state.centerX) * world).toFloat(),
        (size.height / 2.0 + (route.origin.y - state.centerY) * world).toFloat(),
    )
    fun toScreen(point: MapRoutePoint): Offset = origin + Offset(
        ((point.x - route.origin.x) * world).toFloat(),
        ((point.y - route.origin.y) * world).toFloat(),
    )

    val line = Path().apply {
        for ((index, point) in route.line.withIndex()) {
            val p = toScreen(point)
            if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
    }
    drawPath(line, CiuinColors.Ground, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    val dash = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 5.dp.toPx()))
    drawPath(line, CiuinColors.Ink, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash))
    drawHere(origin)

    val destination = toScreen(route.destination)
    drawCircle(CiuinColors.Ground, 9.dp.toPx(), destination)
    drawLevel(Level.Calm, destination, 5.dp.toPx())

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
                drawImage(img, dstOffset = dstOffset, dstSize = dstSize, colorFilter = tileColor)
                continue
            }
            // While loading, stretch the matching part of the nearest loaded ancestor tile (up to 8 levels up), so
            // a fast zoom (the globe fly-in) shows a soft map instead of blank paper.
            for (up in 1..minOf(8, tz)) {
                val ancestor = cache.peek(TileKey(tz - up, wx shr up, ty shr up)) ?: continue
                val part = ancestor.width shr up
                if (part < 1) break
                val mask = (1 shl up) - 1
                drawImage(
                    ancestor,
                    srcOffset = IntOffset((wx and mask) * part, (ty and mask) * part),
                    srcSize = IntSize(part, part),
                    dstOffset = dstOffset,
                    dstSize = dstSize,
                    colorFilter = tileColor,
                )
                break
            }
        }
    }
}
