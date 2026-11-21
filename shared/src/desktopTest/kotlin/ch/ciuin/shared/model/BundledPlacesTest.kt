package ch.ciuin.shared.model

import ch.ciuin.shared.api.ContractJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The committed offline data (data/processed/places_lausanne.geojson) must decode into Place
 * with the app's JSON config, strict on unknown keys. If this fails, the build script and the
 * contract have drifted apart. Desktop-only because it reads a file from the repo.
 */
class BundledPlacesTest {
    private val json = Json(from = ContractJson) { ignoreUnknownKeys = false }

    // Gradle runs tests with the module dir (shared/) as working directory.
    private val file = File("../data/processed/places_lausanne.geojson")

    @Test
    fun everyFeatureDecodesAsPlace() {
        assertTrue(file.isFile, "missing ${file.absolutePath}; run data/scripts/build_places_lausanne.py")
        val features = json.parseToJsonElement(file.readText()).jsonObject.getValue("features").jsonArray
        assertTrue(features.size > 1000, "suspiciously few places: ${features.size}")

        val places = features.map { json.decodeFromJsonElement<Place>(it.jsonObject.getValue("properties")) }

        assertEquals(places.size, places.map { it.placeId }.toSet().size, "duplicate place_id")
        assertTrue(places.all { it.placeId.startsWith("osm:") })
        assertTrue(places.all { it.lat in 46.49..46.55 && it.lon in 6.57..6.68 }, "place outside Lausanne bbox")
        places.filter { it.baselineNoiseDb != null }.forEach {
            assertEquals("sonbase", it.baselineSource, it.placeId)
            assertTrue(it.baselineNoiseDb!! in 20.0..95.0, "implausible noise at ${it.placeId}: ${it.baselineNoiseDb}")
        }
    }
}
