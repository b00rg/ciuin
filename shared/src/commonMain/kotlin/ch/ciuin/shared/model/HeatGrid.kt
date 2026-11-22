package ch.ciuin.shared.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.log10
import kotlin.math.pow

/**
 * One cell of the baseline noise grid from `GET /heatmap` (todo.md §0, "/heatmap response").
 * dB values are levels for the requested period from sonBASE (null where that source has no data).
 * [totalDb] is for grids that only carry one combined value (no road/rail split).
 */
data class HeatPoint(
    val lat: Double,
    val lon: Double,
    val roadDb: Double?,
    val railDb: Double?,
    val totalDb: Double? = null,
    val measurementCount: Int = 1,
) {
    /** Road + rail combined energetically (10·log10 of summed intensities); null if nothing is known. */
    val combinedDb: Double?
        get() {
            if (roadDb == null && railDb == null) return totalDb
            val parts = listOfNotNull(roadDb, railDb)
            if (parts.isEmpty()) return null
            return 10 * log10(parts.sumOf { 10.0.pow(it / 10) })
        }
}

/**
 * Parses the `/heatmap` GeoJSON: a FeatureCollection of Point features at cell centres,
 * `properties: {"road_db": 62.1, "rail_db": null}`. A grid without the split may send one combined
 * `"noise_db"` (or `"value"`) instead. Polygon cells use their first corner. Unparseable features are skipped.
 */
fun parseHeatGrid(geoJson: JsonObject): List<HeatPoint> {
    val features = (geoJson["features"] as? JsonArray) ?: return emptyList()
    return features.mapNotNull { f ->
        runCatching {
            val obj = f.jsonObject
            val geometry = obj["geometry"]!!.jsonObject
            var coords = geometry["coordinates"]!!.jsonArray
            if (geometry["type"]?.jsonPrimitive?.content == "Polygon") coords = coords[0].jsonArray[0].jsonArray
            val props = obj["properties"]?.jsonObject
            fun num(key: String) = (props?.get(key) as? JsonPrimitive)?.doubleOrNull
            HeatPoint(
                lat = coords[1].jsonPrimitive.doubleOrNull!!,
                lon = coords[0].jsonPrimitive.doubleOrNull!!,
                roadDb = num("road_db"),
                railDb = num("rail_db"),
                totalDb = num("noise_db") ?: num("value"),
                measurementCount = (props?.get("n") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: 1,
            )
        }.getOrNull()
    }
}

/** Inverse of [parseHeatGrid]; used by the fake API and tests. */
fun heatGridToGeoJson(points: List<HeatPoint>, resM: Int): JsonObject = buildJsonObject {
    put("type", "FeatureCollection")
    put("res_m", resM)
    put("features", buildJsonArray {
        for (p in points) add(buildJsonObject {
            put("type", "Feature")
            put("geometry", buildJsonObject {
                put("type", "Point")
                put("coordinates", buildJsonArray { add(JsonPrimitive(p.lon)); add(JsonPrimitive(p.lat)) })
            })
            put("properties", buildJsonObject {
                put("road_db", p.roadDb?.let { JsonPrimitive(it) } ?: JsonNull)
                put("rail_db", p.railDb?.let { JsonPrimitive(it) } ?: JsonNull)
            })
        })
    })
}
