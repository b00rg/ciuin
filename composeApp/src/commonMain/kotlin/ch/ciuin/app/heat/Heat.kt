package ch.ciuin.app.heat

import ch.ciuin.app.Category
import ch.ciuin.app.map.latToY
import ch.ciuin.app.map.lonToX
import ch.ciuin.shared.api.NoisePeriod
import ch.ciuin.shared.model.HeatPoint
import ch.ciuin.shared.model.Place
import ch.ciuin.shared.model.SoundClass
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/** One value at a point, in normalised Web Mercator coords (x, y in [0, 1], same as MapState). */
data class HeatSample(val x: Double, val y: Double, val value: Double, val count: Int = 1)

/** Aggregated square cell: top-left [x], [y] and [size] in normalised coords; [mean] of the raw values inside. */
data class HeatCell(val x: Double, val y: Double, val size: Double, val mean: Double, val count: Int)

/** Normalised-coordinate rectangle currently on screen. */
data class ViewBounds(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    operator fun contains(s: HeatSample) = containsPoint(s.x, s.y)

    internal fun containsLocation(lat: Double, lon: Double) = containsPoint(lonToX(lon), latToY(lat))

    private fun containsPoint(x: Double, y: Double): Boolean {
        if (y !in y0..y1 || !x.isFinite()) return false
        // Bounds can wrap at the date line or use the coordinates of an adjacent copy of the map.
        val right = if (x1 < x0) x1 + 1.0 else x1
        return x + kotlin.math.ceil(x0 - x) <= right
    }
}

/**
 * Raw samples for a category. Sound uses the baseline grid (road + rail combined) plus every place
 * (its measured dB, else its sonBASE baseline); the other layers only exist where places have data.
 */
fun samplesFor(
    category: Category,
    places: List<Place>,
    grid: List<HeatPoint>,
    personalScores: Map<String, Double> = emptyMap(),
    noisePeriod: NoisePeriod = NoisePeriod.DAY,
): List<HeatSample> {
    val fromPlaces = places.mapNotNull { p ->
        category.mapValue(p, personalScores[p.placeId], noisePeriod)
            ?.let { HeatSample(lonToX(p.lon), latToY(p.lat), it) }
    }
    if (category != Category.SOUND) return fromPlaces
    return grid.mapNotNull { g -> g.combinedDb?.takeIf { it.isFinite() }?.let { HeatSample(lonToX(g.lon), latToY(g.lat), it) } } + fromPlaces
}

/**
 * Cell level for a zoom: cells are 1/2^level of the world wide, i.e. 1/8 of a tile (~32 dp) on screen,
 * so zoomed out you get a few big city-wide averages and zooming in splits them up.
 * Never finer than the data resolution [dataResM] at [lat], or cells would be mostly empty.
 */
fun cellLevel(zoom: Double, lat: Double, dataResM: Double): Int {
    val worldM = 40_075_016.0 * cos(lat * PI / 180)
    val maxLevel = floor(ln(worldM / dataResM) / ln(2.0)).toInt()
    return (floor(zoom).toInt() + 3).coerceIn(0, maxLevel)
}

/** Averages samples into a 2^level × 2^level grid; only non-empty cells are returned. */
/**
 * How to average a category's raw values. Decibels are logarithmic, so Sound averages sound *energy*
 * (50 dB and 70 dB average to ~67 dB, not 60): a loud street isn't hidden by quiet courtyards next to it.
 * Everything else is a plain mean.
 */
class Averaging(val toLinear: (Double) -> Double, val fromLinear: (Double) -> Double) {
    fun mean(values: List<Double>): Double? =
        if (values.isEmpty()) null else fromLinear(values.sumOf(toLinear) / values.size)

    companion object {
        val PLAIN = Averaging({ it }, { it })
        val DECIBELS = Averaging({ 10.0.pow(it / 10) }, { 10 * log10(it) })

        fun of(category: Category) = if (category == Category.SOUND) DECIBELS else PLAIN
    }
}

fun aggregate(samples: List<HeatSample>, level: Int, averaging: Averaging = Averaging.PLAIN): List<HeatCell> {
    val size = 1.0 / 2.0.pow(level)
    val sums = HashMap<Long, DoubleArray>() // key -> [sum, count]
    for (s in samples) {
        val cx = floor(s.x / size).toLong()
        val cy = floor(s.y / size).toLong()
        val acc = sums.getOrPut((cx shl 32) or (cy and 0xFFFFFFFFL)) { DoubleArray(2) }
        acc[0] += averaging.toLinear(s.value) * s.count
        acc[1] += s.count.toDouble()
    }
    return sums.map { (key, acc) ->
        val cx = key shr 32
        val cy = key and 0xFFFFFFFFL
        HeatCell(cx * size, cy * size, size, averaging.fromLinear(acc[0] / acc[1]), acc[1].toInt())
    }
}

// ---------------------------------------------------------------------------------------------
// Hexagonal bins (the honeycomb look popularised by Uber's H3 / deck.gl hexagon layer; own implementation)

/** One hexagon: centre ([x], [y]) and circumradius [radius] in normalised coords; [mean] of the values inside. */
data class HexCell(val x: Double, val y: Double, val radius: Double, val mean: Double, val count: Int)

private val SQRT3 = sqrt(3.0)

/** Circumradius of a hexagon with the same area as a square cell at [level]. */
fun hexRadius(level: Int): Double = (1.0 / 2.0.pow(level)) * sqrt(2.0 / (3.0 * SQRT3))

/**
 * Bins samples into pointy-top hexagons of circumradius [radius] (axial coordinates, cube rounding)
 * and averages each bin. Only non-empty hexagons are returned.
 */
fun aggregateHex(samples: List<HeatSample>, radius: Double, averaging: Averaging = Averaging.PLAIN): List<HexCell> {
    val sums = HashMap<Long, DoubleArray>() // key -> [sum, count]
    for (s in samples) {
        val (q, r) = hexAt(s.x, s.y, radius)
        val acc = sums.getOrPut((q.toLong() shl 32) or (r.toLong() and 0xFFFFFFFFL)) { DoubleArray(2) }
        acc[0] += averaging.toLinear(s.value) * s.count
        acc[1] += s.count.toDouble()
    }
    return sums.map { (key, acc) ->
        val q = (key shr 32).toInt()
        val r = key.toInt()
        HexCell(
            x = radius * SQRT3 * (q + r / 2.0),
            y = radius * 1.5 * r,
            radius = radius,
            mean = averaging.fromLinear(acc[0] / acc[1]),
            count = acc[1].toInt(),
        )
    }
}

/** Axial (q, r) of the pointy-top hexagon containing (x, y). */
internal fun hexAt(x: Double, y: Double, radius: Double): Pair<Int, Int> {
    val qf = (SQRT3 / 3 * x - y / 3) / radius
    val rf = (2.0 / 3 * y) / radius
    val sf = -qf - rf
    var q = round(qf)
    var r = round(rf)
    val s = round(sf)
    val dq = abs(q - qf)
    val dr = abs(r - rf)
    val ds = abs(s - sf)
    if (dq > dr && dq > ds) q = -r - s else if (dr > ds) r = -q - s
    return q.toInt() to r.toInt()
}

// ---------------------------------------------------------------------------------------------
// Area breakdown ("Lausanne overall" / "This area")

data class Share(val label: String, val fraction: Double)

data class BreakdownSection(val heading: String, val shares: List<Share>)

data class AreaSummary(
    /** Mean raw value in the area (dB, lux, people, 0..2 access score), or null if no data. */
    val mean: Double?,
    val sampleCount: Int,
    val sections: List<BreakdownSection>,
    val roadMeanDb: Double? = null,
    val railMeanDb: Double? = null,
    val accessShares: AccessShares? = null,
    val measuredSoundTypes: BreakdownSection? = null,
)

data class AccessShares(val accessible: Double, val limited: Double, val notAccessible: Double)

/** Sound classes grouped into labels people recognise. */
private val SOUND_GROUPS: List<Pair<String, List<SoundClass>>> = listOf(
    "Crowds & voices" to listOf(SoundClass.SPEECH_CROWD),
    "Traffic" to listOf(SoundClass.TRAFFIC),
    "Constant hum" to listOf(SoundClass.MACHINERY_HUM),
    "Music" to listOf(SoundClass.MUSIC),
    "Construction" to listOf(SoundClass.CONSTRUCTION),
    "Sirens & alarms" to listOf(SoundClass.SIREN, SoundClass.ALARM_BEEP),
    "Other" to listOf(SoundClass.DOG, SoundClass.OTHER),
)

/**
 * Summary of everything inside [bounds] (or everything, when [bounds] is null).
 * Sound: road vs rail share of the baseline noise energy + the mix of sound types we measured.
 * Access: share of places that are accessible / limited / not.
 */
fun summarise(
    category: Category,
    places: List<Place>,
    grid: List<HeatPoint>,
    bounds: ViewBounds?,
    personalScores: Map<String, Double> = emptyMap(),
    noisePeriod: NoisePeriod = NoisePeriod.DAY,
): AreaSummary {
    val placesIn = bounds?.let { view -> places.filter { view.containsLocation(it.lat, it.lon) } } ?: places
    val gridIn = when {
        category != Category.SOUND -> emptyList()
        bounds == null -> grid
        else -> grid.filter { bounds.containsLocation(it.lat, it.lon) }
    }
    val samples = samplesFor(category, placesIn, gridIn, personalScores, noisePeriod)
    val mean = Averaging.of(category).mean(samples.map { it.value })
    val road = gridIn.mapNotNull { it.roadDb?.takeIf(Double::isFinite) }
    val rail = gridIn.mapNotNull { it.railDb?.takeIf(Double::isFinite) }
    val access = if (category == Category.ACCESS && samples.isNotEmpty()) {
        val n = samples.size.toDouble()
        AccessShares(
            accessible = samples.count { it.value == 0.0 } / n,
            limited = samples.count { it.value == 1.0 } / n,
            notAccessible = samples.count { it.value == 2.0 } / n,
        )
    } else null

    val measured = if (category == Category.SOUND && noisePeriod == NoisePeriod.DAY) {
        placesIn.mapNotNull { it.measured }
    } else emptyList()
    val measuredSoundTypes = if (measured.isNotEmpty()) {
        val totalN = measured.sumOf { it.n }.toDouble()
        val shares = SOUND_GROUPS.map { (label, classes) ->
            Share(label, measured.sumOf { m -> m.n * classes.sumOf { m.features.sound(it) } } / totalN)
        }.filter { it.fraction >= 0.01 }.sortedByDescending { it.fraction }.take(5)
        if (shares.isEmpty()) null else BreakdownSection("Measured sound types (${measured.size} places)", shares)
    } else null

    val sections = buildList {
        when (category) {
            Category.SOUND -> {
                // Shares of acoustic energy (not of dB), so a 70 dB railway outweighs a 60 dB road properly.
                val roadEnergy = road.sumOf(Averaging.DECIBELS.toLinear)
                val railEnergy = rail.sumOf(Averaging.DECIBELS.toLinear)
                if (roadEnergy + railEnergy > 0) {
                    add(BreakdownSection("Baseline noise sources", listOf(
                        Share("Road traffic", roadEnergy / (roadEnergy + railEnergy)),
                        Share("Rail", railEnergy / (roadEnergy + railEnergy)),
                    ).sortedByDescending { it.fraction }))
                }
                measuredSoundTypes?.let(::add)
            }
            Category.ACCESS -> {
                if (access != null) {
                    add(BreakdownSection("Wheelchair access (${samples.size} tagged places)", listOf(
                        Share("Accessible", access.accessible),
                        Share("Limited", access.limited),
                        Share("Not accessible", access.notAccessible),
                    )))
                }
            }
            Category.LIGHT, Category.CROWD, Category.PERSONAL -> Unit
        }
    }
    return AreaSummary(
        mean, samples.size, sections,
        roadMeanDb = Averaging.DECIBELS.mean(road),
        railMeanDb = Averaging.DECIBELS.mean(rail),
        accessShares = access,
        measuredSoundTypes = measuredSoundTypes,
    )
}
