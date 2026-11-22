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

const val QUIET_PLACE_RADIUS_METERS = 300
private const val NEARBY_TIMEOUT_MILLIS = 1_500L
private const val EARTH_RADIUS_METERS = 6_371_000.0

data class RoutePoint(val lat: Double, val lon: Double) {
    val isValid: Boolean get() = lat in -90.0..90.0 && lon in -180.0..180.0
}

data class QuietPlaceRoute(
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
    val deltaLon = destination.lon - origin.lon
    val shortDeltaLon = when {
        deltaLon > 180 -> deltaLon - 360
        deltaLon < -180 -> deltaLon + 360
        else -> deltaLon
    }
    val halfLonDifference = sin(shortDeltaLon * PI / 360.0)
    val longitudeWeight = if (kotlin.math.abs(origin.lat) == 90.0 || kotlin.math.abs(destination.lat) == 90.0) {
        0.0
    } else cos(startLat) * cos(endLat)
    val haversine = halfLatDifference * halfLatDifference + longitudeWeight * halfLonDifference * halfLonDifference
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

    val best = quietSpotNear(nearby ?: offlinePlaces(), origin, score) ?: return null
    return routeToPlace(origin, best.place)
}

data class QuietSpot(val place: Place, val distanceMeters: Double, val predictedOverwhelm: Double)

/** Rank cached places exactly like an online quiet-place lookup, including places with unknown hours. */
fun quietSpotNear(
    places: List<Place>,
    origin: RoutePoint,
    score: (FeatureVector) -> Double,
    radiusM: Double = QUIET_PLACE_RADIUS_METERS.toDouble(),
): QuietSpot? {
    if (!origin.isValid || !radiusM.isFinite() || radiusM < 0) return null
    return places.asSequence().mapNotNull { place ->
        if (place.openNow == false) return@mapNotNull null
        val features = place.personalFeatures ?: return@mapNotNull null
        val route = routeToPlace(origin, place) ?: return@mapNotNull null
        if (route.distanceMeters > radiusM) return@mapNotNull null
        val predictedOverwhelm = score(features)
        if (predictedOverwhelm !in 0.0..1.0) return@mapNotNull null

        QuietSpot(place, route.distanceMeters, predictedOverwhelm)
    }.minWithOrNull(compareBy({ it.predictedOverwhelm }, { it.distanceMeters }, { it.place.placeId }))
}

/** A straight-line preview for a selected place while walking directions are fetched. */
fun routeToPlace(origin: RoutePoint, place: Place): QuietPlaceRoute? {
    val destination = RoutePoint(place.lat, place.lon)
    if (!origin.isValid || !destination.isValid || place.placeId.isBlank() || place.name.isBlank()) return null
    return QuietPlaceRoute(origin, destination, place.placeId, place.name, distanceMeters(origin, destination))
}

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
