package ch.ciuin.app.insight

import ch.ciuin.app.route.RoutePoint
import ch.ciuin.app.route.distanceMeters
import ch.ciuin.shared.model.Place
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class DistanceUnit { METRES, KILOMETRES }
data class DistanceTokens(val value: String, val unit: DistanceUnit)
data class NearbyPlace(val place: Place, val distanceMeters: Double)

/** Keep the unit separate so the UI can localise it. */
fun distanceTokens(metres: Double): DistanceTokens {
    require(metres.isFinite() && metres >= 0)
    if (metres < 1_000) return DistanceTokens(metres.roundToInt().toString(), DistanceUnit.METRES)
    val tenths = (metres / 100).roundToLong()
    return DistanceTokens("${tenths / 10}.${tenths % 10}", DistanceUnit.KILOMETRES)
}

fun walkingMinutes(metres: Double): Int {
    require(metres.isFinite() && metres >= 0)
    return (metres / (1.3 * 60)).roundToInt().coerceAtLeast(1)
}

fun nearestPlaces(places: List<Place>, origin: RoutePoint, radiusM: Double = 75.0, limit: Int = 2): List<NearbyPlace> {
    if (!origin.isValid || !radiusM.isFinite() || radiusM < 0 || limit <= 0) return emptyList()
    return places.asSequence().mapNotNull { place ->
        val point = RoutePoint(place.lat, place.lon)
        if (!point.isValid) return@mapNotNull null
        val metres = distanceMeters(origin, point)
        if (metres <= radiusM) NearbyPlace(place, metres) else null
    }.sortedWith(compareBy({ it.distanceMeters }, { it.place.name }, { it.place.placeId })).take(limit).toList()
}
