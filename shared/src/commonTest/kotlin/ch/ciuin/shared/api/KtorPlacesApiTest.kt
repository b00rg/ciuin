package ch.ciuin.shared.api

import ch.ciuin.shared.model.FeatureVector
import ch.ciuin.shared.model.Measurement
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KtorPlacesApiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val placeJson = """
        [{"place_id": "osm:node/1", "name": "Café X", "lat": 46.5191, "lon": 6.5668, "category": "cafe",
          "wheelchair": "yes", "sensory_friendly": null, "quiet_room": false,
          "baseline_noise_db": 58, "baseline_source": "sonbase", "measured": null, "score": null,
          "some_future_field": 1}]
    """.trimIndent()

    private val measurement = Measurement(
        placeId = "osm:node/1", lat = 46.5191, lon = 6.5668, timestamp = "2026-11-21T14:05:00Z", durationS = 30,
        features = FeatureVector(62.4, 78.1, mapOf("speech_crowd" to 1.0), 340.0, 0.04, 7),
        modelVariant = "audio-student-s", device = "test",
    )

    @Test
    fun placesSendsBboxAndDecodesContract() = runTest {
        val engine = MockEngine { req ->
            assertEquals(HttpMethod.Get, req.method)
            assertEquals("/places", req.url.encodedPath)
            assertEquals(BBox.LAUSANNE.toQuery(), req.url.parameters["bbox"])
            respond(placeJson, HttpStatusCode.OK, jsonHeaders)
        }
        val places = KtorPlacesApi("http://backend:8000", engine).places(BBox.LAUSANNE)
        assertEquals(1, places.size)
        assertEquals("Café X", places[0].name)
        assertEquals(58.0, places[0].baselineNoiseDb)
    }

    @Test
    fun nearbyUsesQueryParams() = runTest {
        val engine = MockEngine { req ->
            assertEquals("/places/nearby", req.url.encodedPath)
            assertEquals("300", req.url.parameters["radius"])
            respond("[]", HttpStatusCode.OK, jsonHeaders)
        }
        assertTrue(KtorPlacesApi("http://backend:8000/", engine).nearby(46.5, 6.6).isEmpty())
    }

    @Test
    fun postMeasurementSendsSnakeCaseJson() = runTest {
        val engine = MockEngine { req ->
            assertEquals(HttpMethod.Post, req.method)
            assertEquals("/measurements", req.url.encodedPath)
            val body = ContractJson.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject
            assertEquals("osm:node/1", body["place_id"]!!.jsonPrimitive.content)
            assertTrue("flicker_hz" in body) // encodeDefaults: null fields are still sent
            respond("{}", HttpStatusCode.OK, jsonHeaders)
        }
        KtorPlacesApi("http://backend:8000", engine).postMeasurement(measurement)
    }

    @Test
    fun fallbackUsedWhenBackendFailsAndMeasurementsQueued() = runTest {
        var up = false
        var posted = 0
        val engine = MockEngine { req ->
            if (!up) return@MockEngine respondError(HttpStatusCode.ServiceUnavailable)
            if (req.method == HttpMethod.Post) posted++
            respond(if (req.url.encodedPath == "/places") placeJson else "{}", HttpStatusCode.OK, jsonHeaders)
        }
        val api = FallbackPlacesApi(KtorPlacesApi("http://backend:8000", engine), FakePlacesApi())

        // Backend down: fake places, offline, measurement queued.
        assertEquals(5, api.places(BBox.LAUSANNE).size)
        assertFalse(api.online.value)
        api.postMeasurement(measurement)
        assertEquals(1, api.pendingCount())

        // Backend back: real places, online, queue flushed.
        up = true
        assertEquals("Café X", api.places(BBox.LAUSANNE).single().name)
        assertTrue(api.online.value)
        assertEquals(0, api.pendingCount())
        assertEquals(1, posted)
    }
}
