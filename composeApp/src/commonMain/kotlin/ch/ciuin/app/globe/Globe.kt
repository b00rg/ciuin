package ch.ciuin.app.globe

import ch.ciuin.shared.model.Coverage
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln1p
import kotlin.math.sin
import kotlin.math.sqrt

const val LAUSANNE_LAT = 46.5197
const val LAUSANNE_LON = 6.6323
val LAUSANNE_CENTRE = GlobePoint(LAUSANNE_LAT, LAUSANNE_LON)

private const val RADIANS = PI / 180.0

data class GlobePoint(val lat: Double, val lon: Double)
data class GlobePosition(val x: Double, val y: Double, val visible: Boolean)
data class GlobeSpike(val point: GlobePoint, val height: Double, val measurements: Int)

/** Cell-centred dots avoid overdraw at the poles and a double row at the antimeridian. */
fun globeDots(stepDeg: Double = 2.0): List<GlobePoint> {
    require(stepDeg > 0 && stepDeg <= 180 && -180.0 + stepDeg / 2 > -180.0)
    return buildList {
        var lat = -90.0 + stepDeg / 2
        while (lat < 90.0) {
            var lon = -180.0 + stepDeg / 2
            while (lon < 180.0) {
                if (isLand(lat, lon)) add(GlobePoint(lat, lon))
                lon += stepDeg
            }
            lat += stepDeg
        }
    }
}

/** Relative to the largest count so the densest site reaches full height. */
fun globeSpikes(coverage: Coverage): List<GlobeSpike> {
    val max = coverage.cells.maxOfOrNull { it.measurements } ?: return emptyList()
    if (max <= 0) return emptyList()
    val scale = ln1p(max.toDouble())
    return coverage.cells.mapNotNull { cell ->
        if (cell.measurements <= 0) return@mapNotNull null
        GlobeSpike(GlobePoint(cell.lat, cell.lon), ln1p(cell.measurements.toDouble()) / scale, cell.measurements)
    }
}

/** Orthographic unit disc, with x east and y north. Reuse one projection while drawing a frame. */
class GlobeProjection(val centre: GlobePoint) {
    private val sinLat = sin(centre.lat * RADIANS)
    private val cosLat = cos(centre.lat * RADIANS)

    fun project(point: GlobePoint): GlobePosition {
        val lat = point.lat * RADIANS
        val lon = wrapLongitude(wrapLongitude(point.lon) - wrapLongitude(centre.lon)) * RADIANS
        val sinPoint = sin(lat)
        val cosPoint = cos(lat)
        val depth = sinLat * sinPoint + cosLat * cosPoint * cos(lon)
        return GlobePosition(
            x = cosPoint * sin(lon),
            y = cosLat * sinPoint - sinLat * cosPoint * cos(lon),
            visible = depth >= -1e-12,
        )
    }

    /** Move the disc with the pointer by inverting its movement at the centre. */
    fun drag(deltaX: Double, deltaY: Double): GlobePoint {
        if (!deltaX.isFinite() || !deltaY.isFinite() || (deltaX == 0.0 && deltaY == 0.0)) return centre
        // Clamp pure north/south drags at the pole instead of flipping the longitude.
        if (deltaX == 0.0) {
            val lat = centre.lat - asin(deltaY.coerceIn(-1.0, 1.0)) / RADIANS
            return GlobePoint(lat.coerceIn(-90.0, 90.0), wrapLongitude(centre.lon))
        }
        val largest = maxOf(1.0, kotlin.math.abs(deltaX), kotlin.math.abs(deltaY))
        val x = -deltaX / largest
        val y = -deltaY / largest
        val radius = hypot(x, y)
        val scale = if (largest > 1.0) radius else maxOf(1.0, radius)
        val east = x / scale
        val north = y / scale
        val depth = sqrt((1 - east * east - north * north).coerceAtLeast(0.0))
        val lat = asin((depth * sinLat + north * cosLat).coerceIn(-1.0, 1.0)) / RADIANS
        val lon = atan2(east, depth * cosLat - north * sinLat) / RADIANS
        return GlobePoint(lat, wrapLongitude(wrapLongitude(centre.lon) + lon))
    }
}

internal fun wrapLongitude(lon: Double): Double {
    val remainder = lon % 360.0
    return when {
        remainder >= 180.0 -> remainder - 360.0
        remainder < -180.0 -> remainder + 360.0
        else -> remainder
    }
}
