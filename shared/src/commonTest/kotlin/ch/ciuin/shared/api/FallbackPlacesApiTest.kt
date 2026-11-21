package ch.ciuin.shared.api

import ch.ciuin.shared.model.FeatureVector
import ch.ciuin.shared.model.HeatPoint
import ch.ciuin.shared.model.Measurement
import ch.ciuin.shared.model.SoundClass
import ch.ciuin.shared.model.heatGridToGeoJson
import ch.ciuin.shared.model.parseHeatGrid
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FallbackPlacesApiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val placeJson = """
        [{"place_id": "osm:node/1", "name": "Café X", "lat": 46.5191, "lon": 6.5668, "category": "cafe"}]
    """.trimIndent()

    private val measurement = Measurement(
        placeId = "osm:node/1", lat = 46.5191, lon = 6.5668, timestamp = "2026-11-21T14:05:00Z", durationS = 30,
        features = FeatureVector(62.4, 78.1, SoundClass.entries.associate { it.label to 1.0 / 9 }, 340.0, 0.04, 7),
        modelVariant = "audio-student-s", device = "test",
    )

    @Test
    fun fallsBackWhenDownAndFlushesQueueWhenBack() = runTest {
        var up = false
        var posted = 0
        val engine = MockEngine { req ->
            if (!up) return@MockEngine respondError(HttpStatusCode.ServiceUnavailable)
            if (req.method == HttpMethod.Post) {
                posted++
                return@MockEngine respond("""{"stored": true, "place": null}""", HttpStatusCode.Created, jsonHeaders)
            }
            respond(placeJson, HttpStatusCode.OK, jsonHeaders)
        }
        val api = FallbackPlacesApi(KtorPlacesApi("http://backend:8000", engine), FakePlacesApi())

        // Backend down: fake places, offline, measurement queued.
        assertEquals(5, api.places(BBox.LAUSANNE).size)
        assertFalse(api.online.value)
        assertFalse(api.postMeasurement(measurement).stored)
        assertEquals(1, api.pendingCount())

        // Backend back: real places, online, queue flushed.
        up = true
        assertEquals("Café X", api.places(BBox.LAUSANNE).single().name)
        assertTrue(api.online.value)
        assertEquals(0, api.pendingCount())
        assertEquals(1, posted)
    }

    @Test
    fun rejectedMeasurementIsThrownNotQueued() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.UnprocessableEntity) }
        val api = FallbackPlacesApi(KtorPlacesApi("http://backend:8000", engine), FakePlacesApi())
        assertFailsWith<ClientRequestException> { api.postMeasurement(measurement) }
        assertEquals(0, api.pendingCount())
    }

    @Test
    fun forwardsOpeningHoursFilters() = runTest {
        val at = "2026-11-21T12:00:00Z"
        val engine = MockEngine { req ->
            assertEquals(at, req.url.parameters["at"])
            if (req.url.encodedPath == "/places/nearby") {
                assertEquals("true", req.url.parameters["open_only"])
            }
            respond(placeJson, HttpStatusCode.OK, jsonHeaders)
        }
        val api = FallbackPlacesApi(KtorPlacesApi("http://backend:8000", engine), FakePlacesApi())

        assertEquals("Café X", api.places(BBox.LAUSANNE, at = at).single().name)
        assertEquals("Café X", api.nearby(46.5191, 6.5668, openOnly = true, at = at).single().name)
    }

    @Test
    fun heatGridRoundTripsAndCombinesEnergetically() = runTest {
        val points = listOf(HeatPoint(46.52, 6.63, roadDb = 60.0, railDb = 60.0), HeatPoint(46.53, 6.64, roadDb = 55.0, railDb = null))
        val parsed = parseHeatGrid(heatGridToGeoJson(points, resM = 50))
        assertEquals(points, parsed)
        // Two equal 60 dB sources add up to ~63 dB, not 120.
        assertEquals(63.0, parsed[0].combinedDb!!, 0.1)
        assertEquals(55.0, parsed[1].combinedDb!!, 1e-9)
    }

    @Test
    fun heatGridAcceptsCombinedValueAndEmptyCollection() {
        assertTrue(parseHeatGrid(JsonObject(emptyMap())).isEmpty())
        val json = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"type":"FeatureCollection","features":[{"type":"Feature",
               "geometry":{"type":"Point","coordinates":[6.63,46.52]},"properties":{"noise_db":58.5}}]}""",
        ) as JsonObject
        assertEquals(58.5, assertNotNull(parseHeatGrid(json).single().combinedDb))
    }

    @Test
    fun fakeHeatmapHasRoadAndRail() = runTest {
        val grid = parseHeatGrid(FakePlacesApi().heatmap(BBox.LAUSANNE, "noise"))
        assertTrue(grid.size > 500)
        assertTrue(grid.any { it.railDb != null } && grid.all { it.roadDb != null })
    }
}
