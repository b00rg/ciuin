package ch.ciuin.shared.api

import ch.ciuin.shared.model.Coverage
import ch.ciuin.shared.model.CoverageCell
import ch.ciuin.shared.model.Measurement
import ch.ciuin.shared.model.Place
import kotlinx.serialization.json.JsonObject

/** In-memory stand-in for the backend with 5 hard-coded Lausanne places. */
class FakePlacesApi : PlacesApi {
    private val all = listOf(
        Place("fake:1", "Place de la Riponne", 46.5236, 6.6327, "square", wheelchair = "yes", baselineNoiseDb = 62.0, baselineSource = "sonbase"),
        Place("fake:2", "Gare de Lausanne", 46.5167, 6.6291, "train_station", wheelchair = "yes", baselineNoiseDb = 68.0, baselineSource = "sonbase"),
        Place("fake:3", "Palais de Rumine", 46.5225, 6.6345, "museum", wheelchair = "limited", quietRoom = true, baselineNoiseDb = 45.0, baselineSource = "sonbase"),
        Place("fake:4", "Parc de Mon-Repos", 46.5185, 6.6418, "park", wheelchair = "yes", baselineNoiseDb = 48.0, baselineSource = "sonbase"),
        Place("fake:5", "Ouchy waterfront", 46.5068, 6.6266, "promenade", wheelchair = "yes", baselineNoiseDb = 55.0, baselineSource = "sonbase"),
    )

    /** Made-up numbers, just enough cells to exercise the globe. */
    private val fakeCoverage = Coverage(
        resDeg = 2.0,
        cells = listOf(
            CoverageCell(lat = 47.0, lon = 7.0, wheelchair = 18000, sensoryFriendly = 3, noiseSource = "sonbase", measurements = 0),
            CoverageCell(lat = 53.0, lon = -7.0, wheelchair = 9000, sensoryFriendly = 1, noiseSource = "eea"),
            CoverageCell(lat = 49.0, lon = 3.0, wheelchair = 40000, noiseSource = "eea"),
            CoverageCell(lat = 41.0, lon = -73.0, wheelchair = 30000, sensoryFriendly = 2),
            CoverageCell(lat = 35.0, lon = 139.0, wheelchair = 25000),
        ),
    )

    override suspend fun places(bbox: BBox): List<Place> = all.filter {
        it.lon in bbox.minLon..bbox.maxLon && it.lat in bbox.minLat..bbox.maxLat
    }

    override suspend fun postMeasurement(m: Measurement) = Unit

    override suspend fun heatmap(bbox: BBox, dim: String): JsonObject = JsonObject(emptyMap())

    override suspend fun nearby(lat: Double, lon: Double, radiusM: Int): List<Place> = all

    override suspend fun stats(): Stats = Stats(placesMapped = all.size, measurementsTaken = 0, sensoryFriendlyTags = 0)

    override suspend fun coverage(): Coverage = fakeCoverage
}
