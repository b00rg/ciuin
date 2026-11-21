package ch.ciuin.shared.api

import ch.ciuin.shared.model.FeatureVector
import ch.ciuin.shared.model.Measurement
import ch.ciuin.shared.model.SoundClass
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Checks the URLs/params/bodies KtorPlacesApi sends and that it decodes backend-shaped responses. */
class KtorPlacesApiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        KtorPlacesApi("http://backend:8000/", MockEngine { req -> requests += req; handler(req) })

    private fun MockRequestHandleScope.ok(body: String) = respond(body, HttpStatusCode.OK, jsonHeaders)

    // Same shape backend/main.py returns (place with one measurement)
    private val placeJson = """
        {"place_id":"osm:node/911106747","name":"Starbucks","lat":46.522522,"lon":6.630265,"category":"cafe",
         "wheelchair":"yes","sensory_friendly":null,"quiet_room":false,"baseline_noise_db":43.7,"baseline_source":"sonbase",
         "measured":{"n":1,"features":{"db_mean":62.4,"db_peak":78.1,"sound_classes":{"speech_crowd":0.71,"music":0.12,
           "traffic":0.08,"siren":0.0,"construction":0.0,"alarm_beep":0.0,"dog":0.0,"machinery_hum":0.03,"other":0.06},
           "lux_mean":340.0,"flicker_index":0.04,"people_count":7}},
         "score":{"noise":0.36,"light":1.0,"flicker":0.8,"crowd":0.767,"access":1.0}}
    """.trimIndent()

    @Test
    fun placesSendsBboxAndDecodes() = runTest {
        val places = api { ok("[$placeJson]") }.places(BBox.LAUSANNE)
        val url = requests.single().url
        assertEquals("/places", url.encodedPath)
        assertEquals("6.58,46.5,6.67,46.54", url.parameters["bbox"])
        assertEquals("Starbucks", places.single().name)
        assertEquals(1, places.single().measured?.n)
        assertEquals(0.36, places.single().score?.noise)
    }

    @Test
    fun trailingSlashInBaseUrlIsHandled() = runTest {
        api { ok("""{"places_mapped":0,"measurements_taken":0,"sensory_friendly_tags":0}""") }.stats()
        assertEquals("http://backend:8000/stats", requests.single().url.toString()) // no "//stats"
    }

    @Test
    fun nearbySendsLatLonRadius() = runTest {
        api { ok("[]") }.nearby(46.5236, 6.6327, 150)
        val p = requests.single().url.parameters
        assertEquals("/places/nearby", requests.single().url.encodedPath)
        assertEquals(listOf("46.5236", "6.6327", "150"), listOf(p["lat"], p["lon"], p["radius"]))
    }

    @Test
    fun openOnlyAndAtAreSentOnlyWhenSet() = runTest {
        val api = api { ok("[]") }
        api.nearby(46.5, 6.6, 300, openOnly = true, at = "2026-11-21T20:00:00Z")
        api.nearby(46.5, 6.6)
        api.places(BBox.LAUSANNE, at = "2026-11-21T20:00:00Z")
        val (withFlags, plain, places) = requests.map { it.url.parameters }
        assertEquals("true", withFlags["open_only"])
        assertEquals("2026-11-21T20:00:00Z", withFlags["at"])
        assertNull(plain["open_only"])
        assertNull(plain["at"])
        assertEquals("2026-11-21T20:00:00Z", places["at"])
    }

    @Test
    fun postMeasurementSendsEveryContractFieldAndDecodesResult() = runTest {
        val m = Measurement(
            placeId = "osm:node/911106747", lat = 46.522522, lon = 6.630265, timestamp = "2026-11-21T14:05:00Z",
            durationS = 30,
            features = FeatureVector(62.4, 78.1, SoundClass.entries.associate { it.label to 1.0 / 9 }, 340.0, 0.04, 7),
            flickerHz = null, modelVariant = "audio-student-s", device = "pixel-7",
        )
        val result = api { ok("""{"stored":true,"place":$placeJson}""") }.postMeasurement(m)

        val req = requests.single()
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("/measurements", req.url.encodedPath)
        val sent = Json.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject
        assertEquals(
            setOf("place_id", "lat", "lon", "timestamp", "duration_s", "features", "flicker_hz", "model_variant", "device"),
            sent.keys,
        )
        assertEquals("null", sent["flicker_hz"].toString()) // sent explicitly, not dropped
        assertTrue(result.stored)
        assertEquals("osm:node/911106747", result.place?.placeId)
    }

    @Test
    fun unknownPlaceGivesNullPlace() = runTest {
        val m = Measurement("osm:node/1", 40.7, -74.0, "2026-11-21T14:05:00Z", 30,
            FeatureVector(50.0, 60.0, SoundClass.entries.associate { it.label to 1.0 / 9 }, 100.0, 0.0, 0),
            modelVariant = "s", device = "d")
        assertNull(api { ok("""{"stored":true,"place":null}""") }.postMeasurement(m).place)
    }

    @Test
    fun coverageAndStatsDecode() = runTest {
        val cov = api {
            if (it.url.encodedPath == "/coverage") ok("""{"res_deg":0.5,"cells":[{"lat":46.75,"lon":6.75,"wheelchair":592,"sensory_friendly":0,"noise_source":"sonbase","measurements":1}]}""")
            else ok("""{"places_mapped":2873,"measurements_taken":1,"sensory_friendly_tags":0}""")
        }
        val c = cov.coverage(0.5)
        assertEquals("0.5", requests.last().url.parameters["res_deg"])
        assertEquals(592, c.cells.single().wheelchair)
        assertEquals(2873, cov.stats().placesMapped)
    }

    @Test
    fun httpErrorsThrow() = runTest {
        assertFailsWith<ClientRequestException> {
            api { respond("""{"detail":"bad"}""", HttpStatusCode.UnprocessableEntity, jsonHeaders) }.places(BBox.LAUSANNE)
        }
    }
}
