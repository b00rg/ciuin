package ch.ciuin.shared.api

import ch.ciuin.shared.model.FeatureVector
import ch.ciuin.shared.model.Measurement
import ch.ciuin.shared.model.SoundClass
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end against a running backend. Skipped unless CIUIN_LIVE_API is set, because it WRITES a
 * measurement. Point it at a throwaway DB:
 *   cd backend && CIUIN_DB=/tmp/e2e.db uvicorn main:app --port 8000
 *   CIUIN_LIVE_API=http://localhost:8000 ./gradlew :shared:desktopTest --tests '*LiveTest'
 */
class KtorPlacesApiLiveTest {
    private val url = System.getenv("CIUIN_LIVE_API")?.takeIf { it.isNotBlank() }

    @Test
    fun roundTripAgainstRealBackend() = runBlocking {
        if (url == null) {
            println("CIUIN_LIVE_API not set; skipping live test")
            return@runBlocking
        }
        val api = KtorPlacesApi(url)
        try {
            val places = api.places(BBox.LAUSANNE)
            assertTrue(places.size > 2000, "only ${places.size} places")
            val target = places.first { it.wheelchair == "yes" }
            val before = target.measured?.n ?: 0

            val result = api.postMeasurement(
                Measurement(
                    placeId = target.placeId, lat = target.lat, lon = target.lon,
                    timestamp = "2026-11-21T16:00:00Z", durationS = 30,
                    features = FeatureVector(58.0, 71.0, SoundClass.entries.associate { it.label to 1.0 / 9 }, 250.0, 0.02, 4),
                    flickerHz = null, modelVariant = "live-test", device = "jvm",
                ),
            )
            val updated = assertNotNull(result.place)
            assertEquals(before + 1, updated.measured?.n)
            assertNotNull(updated.score)

            val near = api.nearby(target.lat, target.lon, 100)
            assertEquals(target.placeId, near.first().placeId, "nearest place to itself")
            assertTrue(api.stats().measurementsTaken >= 1)
            assertTrue(api.coverage(2.0).cells.any { it.measurements >= 1 })
            println("live OK: ${places.size} places; measured ${updated.name} -> n=${updated.measured?.n}")
        } finally {
            api.close()
        }
    }
}
