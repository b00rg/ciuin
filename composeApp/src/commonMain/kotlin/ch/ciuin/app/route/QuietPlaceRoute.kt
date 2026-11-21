package ch.ciuin.app.route

import ch.ciuin.app.personalFeatures
import ch.ciuin.shared.api.ContractJson
import ch.ciuin.shared.api.PlacesApi
import ch.ciuin.shared.model.FeatureVector
import ch.ciuin.shared.model.Place
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal const val QUIET_PLACE_RADIUS_METERS = 300
private const val NEARBY_TIMEOUT_MILLIS = 1_500L
private const val EARTH_RADIUS_METERS = 6_371_000.0

internal data class RoutePoint(val lat: Double, val lon: Double) {
    val isValid: Boolean get() = lat in -90.0..90.0 && lon in -180.0..180.0
}

internal data class QuietPlaceRoute(
    val origin: RoutePoint,
    val destination: RoutePoint,
    val destinationId: String,
    val destinationName: String,
    val distanceMeters: Double,
)

internal fun distanceMeters(origin: RoutePoint, destination: RoutePoint): Double {
    val startLat = origin.lat * PI / 180.0
    val endLat = destination.lat * PI / 180.0
    val halfLatDifference = sin((endLat - startLat) / 2.0)
    val halfLonDifference = sin((destination.lon - origin.lon) * PI / 360.0)
    val haversine = halfLatDifference * halfLatDifference +
        cos(startLat) * cos(endLat) * halfLonDifference * halfLonDifference
    return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
}

internal suspend fun findQuietPlace(
    api: PlacesApi,
    origin: RoutePoint,
    score: (FeatureVector) -> Double,
    offlinePlaces: suspend () -> List<Place>,
): QuietPlaceRoute? {
    if (!origin.isValid) return null

    val nearby = try {
        withTimeoutOrNull(NEARBY_TIMEOUT_MILLIS) {
            api.nearby(origin.lat, origin.lon, radiusM = QUIET_PLACE_RADIUS_METERS, openOnly = true)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    val candidates = nearby ?: offlinePlaces()
    val best = candidates.asSequence().mapNotNull { place ->
        if (place.openNow == false || place.placeId.isBlank() || place.name.isBlank()) return@mapNotNull null
        val features = place.personalFeatures ?: return@mapNotNull null
        val destination = RoutePoint(place.lat, place.lon)
        if (!destination.isValid) return@mapNotNull null
        val distance = distanceMeters(origin, destination)
        if (distance > QUIET_PLACE_RADIUS_METERS) return@mapNotNull null
        val predictedOverwhelm = score(features)
        if (predictedOverwhelm !in 0.0..1.0) return@mapNotNull null

        ScoredPlace(place, destination, distance, predictedOverwhelm)
    }.minWithOrNull(compareBy({ it.predictedOverwhelm }, { it.distance }, { it.place.placeId })) ?: return null

    return QuietPlaceRoute(origin, best.destination, best.place.placeId, best.place.name, best.distance)
}

private data class ScoredPlace(
    val place: Place,
    val destination: RoutePoint,
    val distance: Double,
    val predictedOverwhelm: Double,
)

internal fun decodeOfflinePlaces(geoJson: String): List<Place> {
    val features = ContractJson.parseToJsonElement(geoJson).jsonObject["features"] as? JsonArray ?: return emptyList()
    return features.mapNotNull { feature ->
        val properties = (feature as? JsonObject)?.get("properties") as? JsonObject ?: return@mapNotNull null
        try {
            ContractJson.decodeFromJsonElement<Place>(properties)
        } catch (_: SerializationException) {
            null
        }
    }
}
