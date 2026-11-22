package ch.ciuin.shared.api

import ch.ciuin.shared.model.Coverage
import ch.ciuin.shared.model.Measurement
import ch.ciuin.shared.model.MeasurementResult
import ch.ciuin.shared.model.Place
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Backend API (todo.md §0). Implementations: [KtorPlacesApi] (real backend) and [FakePlacesApi]
 * (5 hard-coded places, for UI work without a backend). Network/HTTP errors are thrown.
 */
interface PlacesApi {
    /**
     * GET /places?bbox=minLon,minLat,maxLon,maxLat[&at=]
     * @param at ISO-8601 time to evaluate Place.openNow at (e.g. "2026-11-21T20:00:00Z"); null = now.
     */
    suspend fun places(bbox: BBox, at: String? = null): List<Place>

    /** POST /measurements -> the place with updated aggregates (null if place_id is unknown) */
    suspend fun postMeasurement(m: Measurement): MeasurementResult

    /** GET /heatmap?bbox=...&dim=noise -> GeoJSON grid */
    suspend fun heatmap(bbox: BBox, dim: String): JsonObject

    /**
     * GET /places/nearby?lat=..&lon=..&radius=300 -> places sorted by distance, with mean features.
     * @param openOnly drop places known to be closed (unknown hours are kept). Use for the quiet-place pick.
     * @param at ISO-8601 time for openNow / openOnly; null = now.
     */
    suspend fun nearby(lat: Double, lon: Double, radiusM: Int = 300, openOnly: Boolean = false, at: String? = null): List<Place>

    /** GET /stats */
    suspend fun stats(): Stats

    /** GET /coverage?res_deg= -> coarse grid for the 3D globe: 2.0 = global tier, 1.0 = Europe tier */
    suspend fun coverage(resDeg: Double = 2.0): Coverage
}

data class BBox(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double) {
    fun toQuery(): String = "$minLon,$minLat,$maxLon,$maxLat"

    companion object {
        val LAUSANNE = BBox(minLon = 6.55, minLat = 46.50, maxLon = 6.67, maxLat = 46.54)
    }
}

@Serializable
data class Stats(
    @SerialName("places_mapped") val placesMapped: Int,
    @SerialName("measurements_taken") val measurementsTaken: Int,
    @SerialName("sensory_friendly_tags") val sensoryFriendlyTags: Int,
)
