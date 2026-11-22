@file:OptIn(kotlin.time.ExperimentalTime::class)

package ch.ciuin.app

import ch.ciuin.app.insight.OpenStatus
import ch.ciuin.app.insight.placeHours
import ch.ciuin.app.route.RoutePoint
import ch.ciuin.app.route.decodeOfflinePlaces
import ch.ciuin.app.route.distanceMeters
import ch.ciuin.shared.api.BBox
import ch.ciuin.shared.api.NoisePeriod
import ch.ciuin.shared.api.PlacesApi
import ch.ciuin.shared.api.Stats
import ch.ciuin.shared.api.readBundledCoverage
import ch.ciuin.shared.model.Measurement
import ch.ciuin.shared.model.MeasurementResult
import ch.ciuin.shared.model.Place
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Instant

internal const val BUNDLED_PLACES_NAME = "places_lausanne"
internal const val BUNDLED_PLACES_EXTENSION = "geojson"
internal const val BUNDLED_PLACES_FILE = "$BUNDLED_PLACES_NAME.$BUNDLED_PLACES_EXTENSION"

internal class BundledPlacesApi(readGeoJson: () -> String?) : PlacesApi {
    private val dataset by lazy { readGeoJson()?.let(::decodeOfflinePlaces) }
    val available: Boolean get() = dataset != null

    override suspend fun places(bbox: BBox, at: String?): List<Place> {
        val time = at?.let(Instant::parse) ?: Clock.System.now()
        return dataset.orEmpty().filter {
            it.lon in bbox.minLon..bbox.maxLon && it.lat in bbox.minLat..bbox.maxLat
        }.map { it.withOpeningStatus(time) }
    }

    override suspend fun nearby(lat: Double, lon: Double, radiusM: Int, openOnly: Boolean, at: String?): List<Place> {
        val origin = RoutePoint(lat, lon)
        if (!origin.isValid || radiusM < 0) return emptyList()
        val time = at?.let(Instant::parse) ?: Clock.System.now()
        return dataset.orEmpty().asSequence()
            .map { place -> place to distanceMeters(origin, RoutePoint(place.lat, place.lon)) }
            .filter { (_, distance) -> distance <= radiusM }
            .sortedBy { (_, distance) -> distance }
            .map { (place, _) -> place.withOpeningStatus(time) }
            .filter { !openOnly || it.openNow != false }
            .toList()
    }

    override suspend fun stats(): Stats {
        val places = dataset.orEmpty()
        return Stats(
            placesMapped = places.size,
            measurementsTaken = places.sumOf { it.measured?.n ?: 0 },
            sensoryFriendlyTags = places.count { it.sensoryFriendly != null },
        )
    }

    override suspend fun heatmap(bbox: BBox, dim: String, period: NoisePeriod?) = buildJsonObject {
        put("type", "FeatureCollection")
        put("dim", dim)
        period?.let { put("period", it.name.lowercase()) }
        put("available", false)
        put("features", JsonArray(emptyList()))
    }

    override suspend fun postMeasurement(m: Measurement) = MeasurementResult(stored = false)

    override suspend fun coverage(resDeg: Double) = readBundledCoverage(resDeg)
}

private fun Place.withOpeningStatus(time: Instant): Place {
    val open = when (placeHours(this, time).status) {
        OpenStatus.OPEN -> true
        OpenStatus.CLOSED -> false
        OpenStatus.UNKNOWN -> null
    }
    return if (openNow == open) this else copy(openNow = open)
}
