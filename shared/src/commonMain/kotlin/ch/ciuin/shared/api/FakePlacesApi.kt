package ch.ciuin.shared.api

import ch.ciuin.shared.model.Coverage
import ch.ciuin.shared.model.FeatureVector
import ch.ciuin.shared.model.HeatPoint
import ch.ciuin.shared.model.MeasuredAggregate
import ch.ciuin.shared.model.Measurement
import ch.ciuin.shared.model.MeasurementResult
import ch.ciuin.shared.model.Place
import ch.ciuin.shared.model.heatGridToGeoJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt

/** In-memory stand-in for the backend with 5 hard-coded Lausanne places. */
class FakePlacesApi : PlacesApi {
    private val all = listOf(
        Place("fake:1", "Place de la Riponne", 46.5236, 6.6327, "square", wheelchair = "yes", baselineNoiseDb = 62.0, baselineSource = "sonbase",
            measured = fakeMeasured(n = 1, db = 60.0, lux = 300.0, flicker = 0.03, people = 7, top = "traffic")),
        Place("fake:2", "Gare de Lausanne", 46.5167, 6.6291, "train_station", wheelchair = "yes", baselineNoiseDb = 68.0, baselineSource = "sonbase",
            measured = fakeMeasured(n = 3, db = 71.0, lux = 820.0, flicker = 0.12, people = 24, top = "speech_crowd")),
        Place("fake:3", "Palais de Rumine", 46.5225, 6.6345, "museum", wheelchair = "limited", quietRoom = true, baselineNoiseDb = 45.0, baselineSource = "sonbase",
            measured = fakeMeasured(n = 2, db = 41.0, lux = 80.0, flicker = 0.01, people = 2, top = "other")),
        Place("fake:4", "Parc de Mon-Repos", 46.5185, 6.6418, "park", wheelchair = "yes", baselineNoiseDb = 48.0, baselineSource = "sonbase"),
        Place("fake:5", "Ouchy waterfront", 46.5068, 6.6266, "promenade", wheelchair = "yes", baselineNoiseDb = 55.0, baselineSource = "sonbase"),
    )

    override suspend fun places(bbox: BBox, at: String?): List<Place> = all.filter {
        it.lon in bbox.minLon..bbox.maxLon && it.lat in bbox.minLat..bbox.maxLat
    }

    override suspend fun postMeasurement(m: Measurement): MeasurementResult =
        MeasurementResult(stored = true, place = all.firstOrNull { it.placeId == m.placeId })

    /** Synthetic road + rail noise grid (100 m), built once. */
    private val fakeHeat: JsonObject by lazy {
        buildJsonObject {
            heatGridToGeoJson(fakeNoiseGrid(BBox.LAUSANNE, stepM = 100.0), resM = 100).forEach { (key, value) -> put(key, value) }
            put("dim", "noise")
            put("period", NoisePeriod.DAY.queryValue)
        }
    }

    override suspend fun heatmap(bbox: BBox, dim: String, period: NoisePeriod?): JsonObject =
        if (dim == "noise" && period != NoisePeriod.NIGHT) fakeHeat
        else buildJsonObject {
            put("type", "FeatureCollection")
            put("dim", dim)
            period?.let { put("period", it.queryValue) }
            put("available", false)
            put("features", JsonArray(emptyList()))
        }

    override suspend fun nearby(lat: Double, lon: Double, radiusM: Int, openOnly: Boolean, at: String?): List<Place> =
        if (openOnly) all.filter { it.openNow != false } else all

    override suspend fun stats(): Stats = Stats(placesMapped = all.size, measurementsTaken = 0, sensoryFriendlyTags = 0)

    override suspend fun coverage(resDeg: Double): Coverage = readBundledCoverage(resDeg)
}

/** Made-up measured aggregate so every map layer has something to show offline. */
private fun fakeMeasured(n: Int, db: Double, lux: Double, flicker: Double, people: Int, top: String): MeasuredAggregate {
    val classes = ch.ciuin.shared.model.SoundClass.entries.associate { it.label to if (it.label == top) 0.6 else 0.05 }
    return MeasuredAggregate(n, FeatureVector(db, db + 12, classes, lux, flicker, people))
}

// ---- Synthetic noise grid: loud next to a few rough main-road / railway polylines, decaying with distance. ----

private typealias LatLon = Pair<Double, Double>

private val FAKE_RAIL: List<LatLon> = listOf(
    46.5205 to 6.585, 46.5185 to 6.605, 46.5167 to 6.6291, 46.5160 to 6.645, 46.5130 to 6.665,
)
private val FAKE_ROADS: List<List<LatLon>> = listOf(
    listOf(46.5175 to 6.590, 46.5200 to 6.620, 46.5210 to 6.640, 46.5195 to 6.665), // east-west artery
    listOf(46.5070 to 6.6266, 46.5167 to 6.6291, 46.5236 to 6.6327, 46.5350 to 6.6350), // Ouchy - Gare - centre - north
    listOf(46.5236 to 6.6327, 46.5300 to 6.6150, 46.5380 to 6.590), // centre - north-west
    listOf(46.5068 to 6.600, 46.5068 to 6.6266, 46.5100 to 6.660), // lakeside
)
private const val LAKE_SHORE_LAT = 46.5060

private fun fakeNoiseGrid(bbox: BBox, stepM: Double): List<HeatPoint> {
    val midLat = (bbox.minLat + bbox.maxLat) / 2
    val mPerDegLat = 111_320.0
    val mPerDegLon = 111_320.0 * cos(midLat * kotlin.math.PI / 180)
    val dLat = stepM / mPerDegLat
    val dLon = stepM / mPerDegLon
    fun distM(a: LatLon, line: List<LatLon>): Double = line.zipWithNext().minOf { (p, q) ->
        // Point-to-segment distance in a local flat projection (fine at city scale).
        val px = (a.second - p.second) * mPerDegLon; val py = (a.first - p.first) * mPerDegLat
        val qx = (q.second - p.second) * mPerDegLon; val qy = (q.first - p.first) * mPerDegLat
        val t = ((px * qx + py * qy) / (qx * qx + qy * qy)).coerceIn(0.0, 1.0)
        val dx = px - t * qx; val dy = py - t * qy
        sqrt(dx * dx + dy * dy)
    }
    val out = ArrayList<HeatPoint>()
    var lat = bbox.minLat + dLat / 2
    while (lat < bbox.maxLat) {
        var lon = bbox.minLon + dLon / 2
        while (lon < bbox.maxLon) {
            if (lat > LAKE_SHORE_LAT) {
                val here = lat to lon
                val road = 42.0 + 28.0 * exp(-FAKE_ROADS.minOf { distM(here, it) } / 120.0)
                val railD = distM(here, FAKE_RAIL)
                val rail = if (railD < 600) 35.0 + 33.0 * exp(-railD / 150.0) else null
                out += HeatPoint(lat, lon, roadDb = road, railDb = rail)
            }
            lon += dLon
        }
        lat += dLat
    }
    return out
}
