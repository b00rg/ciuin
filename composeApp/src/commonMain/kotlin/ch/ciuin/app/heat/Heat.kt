package ch.ciuin.app.heat

import ch.ciuin.app.Category
import ch.ciuin.app.map.latToY
import ch.ciuin.app.map.lonToX
import ch.ciuin.shared.model.HeatPoint
import ch.ciuin.shared.model.Place
import ch.ciuin.shared.model.SoundClass
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/** One value at a point, in normalised Web Mercator coords (x, y in [0, 1], same as MapState). */
data class HeatSample(val x: Double, val y: Double, val value: Double)

/** Aggregated square cell: top-left [x], [y] and [size] in normalised coords; [mean] of the raw values inside. */
data class HeatCell(val x: Double, val y: Double, val size: Double, val mean: Double, val count: Int)

/** Normalised-coordinate rectangle currently on screen. */
data class ViewBounds(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    operator fun contains(s: HeatSample) = s.x in x0..x1 && s.y in y0..y1
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
): List<HeatSample> {
    val fromPlaces = places.mapNotNull { p ->
        category.raw(p, personalScores[p.placeId])?.let { HeatSample(lonToX(p.lon), latToY(p.lat), it) }
    }
    if (category != Category.SOUND) return fromPlaces
    return grid.mapNotNull { g -> g.combinedDb?.let { HeatSample(lonToX(g.lon), latToY(g.lat), it) } } + fromPlaces
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
fun aggregate(samples: List<HeatSample>, level: Int): List<HeatCell> {
    val size = 1.0 / 2.0.pow(level)
    val sums = HashMap<Long, DoubleArray>() // key -> [sum, count]
    for (s in samples) {
        val cx = floor(s.x / size).toLong()
        val cy = floor(s.y / size).toLong()
        val acc = sums.getOrPut((cx shl 32) or (cy and 0xFFFFFFFFL)) { DoubleArray(2) }
        acc[0] += s.value
        acc[1] += 1.0
    }
    return sums.map { (key, acc) ->
        val cx = key shr 32
        val cy = key and 0xFFFFFFFFL
        HeatCell(cx * size, cy * size, size, acc[0] / acc[1], acc[1].toInt())
    }
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
)

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
): AreaSummary {
    fun inView(lat: Double, lon: Double) = bounds == null || HeatSample(lonToX(lon), latToY(lat), 0.0) in bounds
    val placesIn = places.filter { inView(it.lat, it.lon) }
    val gridIn = if (category == Category.SOUND) grid.filter { inView(it.lat, it.lon) } else emptyList()
    val samples = samplesFor(category, placesIn, gridIn, personalScores)
    val mean = samples.takeIf { it.isNotEmpty() }?.let { s -> s.sumOf { it.value } / s.size }

    val sections = buildList {
        when (category) {
            Category.SOUND -> {
                // Shares of acoustic energy (not of dB), so a 70 dB railway outweighs a 60 dB road properly.
                val road = gridIn.sumOf { g -> g.roadDb?.let { 10.0.pow(it / 10) } ?: 0.0 }
                val rail = gridIn.sumOf { g -> g.railDb?.let { 10.0.pow(it / 10) } ?: 0.0 }
                if (road + rail > 0) {
                    add(BreakdownSection("Baseline noise sources", listOf(
                        Share("Road traffic", road / (road + rail)),
                        Share("Rail", rail / (road + rail)),
                    ).sortedByDescending { it.fraction }))
                }
                val measured = placesIn.mapNotNull { it.measured }
                if (measured.isNotEmpty()) {
                    val totalN = measured.sumOf { it.n }.toDouble()
                    val shares = SOUND_GROUPS.map { (label, classes) ->
                        Share(label, measured.sumOf { m -> m.n * classes.sumOf { m.features.sound(it) } } / totalN)
                    }.filter { it.fraction >= 0.01 }.sortedByDescending { it.fraction }.take(5)
                    add(BreakdownSection("Measured sound types (${measured.size} places)", shares))
                }
            }
            Category.ACCESS -> {
                val tagged = placesIn.mapNotNull { Category.ACCESS.raw(it) }
                if (tagged.isNotEmpty()) {
                    val n = tagged.size.toDouble()
                    add(BreakdownSection("Wheelchair access (${tagged.size} tagged places)", listOf(
                        Share("Accessible", tagged.count { it == 0.0 } / n),
                        Share("Limited", tagged.count { it == 1.0 } / n),
                        Share("Not accessible", tagged.count { it == 2.0 } / n),
                    )))
                }
            }
            Category.LIGHT, Category.CROWD, Category.PERSONAL -> Unit
        }
    }
    return AreaSummary(mean, samples.size, sections)
}
