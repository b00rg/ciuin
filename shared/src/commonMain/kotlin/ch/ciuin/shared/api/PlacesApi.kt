package ch.ciuin.shared.api

import ch.ciuin.shared.model.Coverage
import ch.ciuin.shared.model.Measurement
import ch.ciuin.shared.model.Place
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Backend API (todo.md §0). The real implementation will be a Ktor client
 * (engines already on the classpath: OkHttp on Android, CIO on desktop) configured with [ContractJson].
 */
interface PlacesApi {
    /** GET /places?bbox=minLon,minLat,maxLon,maxLat */
    suspend fun places(bbox: BBox): List<Place>

    /** POST /measurements */
    suspend fun postMeasurement(m: Measurement)

    /** GET /heatmap?bbox=...&dim=noise -> GeoJSON grid */
    suspend fun heatmap(bbox: BBox, dim: String): JsonObject

    /** GET /places/nearby?lat=..&lon=..&radius=300 -> places sorted by distance, with mean features */
    suspend fun nearby(lat: Double, lon: Double, radiusM: Int = 300): List<Place>

    /** GET /stats */
    suspend fun stats(): Stats

    /** GET /coverage?res_deg= -> coarse grid for the 3D globe: 2.0 = global tier, 0.5 = Europe tier */
    suspend fun coverage(resDeg: Double = 2.0): Coverage
}

data class BBox(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double) {
    fun toQuery(): String = "$minLon,$minLat,$maxLon,$maxLat"

    companion object {
        val LAUSANNE = BBox(minLon = 6.58, minLat = 46.50, maxLon = 6.67, maxLat = 46.54)
    }
}

@Serializable
data class Stats(
    @SerialName("places_mapped") val placesMapped: Int,
    @SerialName("measurements_taken") val measurementsTaken: Int,
    @SerialName("sensory_friendly_tags") val sensoryFriendlyTags: Int,
)
