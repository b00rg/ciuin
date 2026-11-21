package ch.ciuin.app.map

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Camera for a Web Mercator slippy map.
 *
 * The centre is stored in normalised world coordinates: x, y in [0, 1], where (0, 0) is the
 * top-left corner of the world (lon -180, lat ~85.05). At zoom z the world is `tilePx * 2^z` pixels wide.
 */
@Stable
class MapState(lat: Double, lon: Double, zoom: Double) {
    var centerX by mutableStateOf(lonToX(lon)); private set
    var centerY by mutableStateOf(latToY(lat)); private set
    var zoom by mutableStateOf(zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)); private set

    val centerLat: Double get() = yToLat(centerY)
    val centerLon: Double get() = xToLon(centerX)

    fun worldSize(tilePx: Float): Double = tilePx * 2.0.pow(zoom)

    /** Drag the map by a screen-space delta (finger moves right → map content moves right). */
    fun pan(delta: Offset, tilePx: Float) {
        val w = worldSize(tilePx)
        centerX = wrap01(centerX - delta.x / w)
        centerY = (centerY - delta.y / w).coerceIn(0.0, 1.0)
    }

    /** Zoom by [factor] (2.0 = one level in), keeping the world point under [focus] fixed on screen. */
    fun zoomAt(factor: Double, focus: Offset, viewport: Size, tilePx: Float) {
        val newZoom = (zoom + log2(factor)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (newZoom == zoom) return
        val dx = focus.x - viewport.width / 2.0
        val dy = focus.y - viewport.height / 2.0
        val w = worldSize(tilePx)
        val fx = centerX + dx / w
        val fy = centerY + dy / w
        zoom = newZoom
        val nw = worldSize(tilePx)
        centerX = wrap01(fx - dx / nw)
        centerY = (fy - dy / nw).coerceIn(0.0, 1.0)
    }

    fun centerOn(lat: Double, lon: Double, zoom: Double = this.zoom) {
        centerX = lonToX(lon)
        centerY = latToY(lat)
        this.zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    /** Screen position of a lat/lon, taking the shortest way round the antimeridian. */
    fun toScreen(lat: Double, lon: Double, viewport: Size, tilePx: Float): Offset {
        val w = worldSize(tilePx)
        var dx = lonToX(lon) - centerX
        if (dx > 0.5) dx -= 1.0 else if (dx < -0.5) dx += 1.0
        val dy = latToY(lat) - centerY
        return Offset((viewport.width / 2.0 + dx * w).toFloat(), (viewport.height / 2.0 + dy * w).toFloat())
    }

    companion object {
        const val MIN_ZOOM = 2.0
        const val MAX_ZOOM = 19.0
    }
}

private const val MAX_LAT = 85.05112878

fun lonToX(lon: Double): Double = (lon + 180.0) / 360.0

fun latToY(lat: Double): Double {
    val r = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
    return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
}

fun xToLon(x: Double): Double = x * 360.0 - 180.0

fun yToLat(y: Double): Double = atan(sinh(PI * (1.0 - 2.0 * y))) * 180.0 / PI

private fun wrap01(v: Double): Double = v - floor(v)
