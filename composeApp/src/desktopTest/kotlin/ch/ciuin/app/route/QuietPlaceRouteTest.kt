package ch.ciuin.app.route

import ch.ciuin.app.personal.PersonalModelSession
import ch.ciuin.shared.api.FakePlacesApi
import ch.ciuin.shared.api.PlacesApi
import ch.ciuin.shared.model.FeatureVector
import ch.ciuin.shared.model.MeasuredAggregate
import ch.ciuin.shared.model.Place
import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuietPlaceRouteTest {
    private val origin = RoutePoint(0.0, 0.0)
    private val score: (FeatureVector) -> Double = { requireNotNull(it.dbMean) / 100.0 }

    @Test
    fun picksTheLowestScoreInsideTheRadiusUsingMeasuredFeaturesWhenAvailable() = runBlocking {
        val winner = place("winner", 0.002697, db = 40.0)
        val api = nearbyApi(origin) {
            listOf(
                place("louder-measured", 0.001, db = 20.0).copy(measured = MeasuredAggregate(2, features(70.0))),
                place("closed", 0.001, db = 10.0).copy(openNow = false),
                place("outside", 0.002699, db = 10.0),
                place("invalid-coordinate", Double.NaN, db = 10.0),
                place("invalid-score", 0.001, db = 120.0),
                place("missing-features", 0.001, db = 10.0).copy(prior = null),
                winner,
            )
        }

        val result = assertNotNull(findQuietPlace(api, origin, score) { error("Unexpected offline lookup") })
        assertEquals(origin, result.origin)
        assertEquals(RoutePoint(winner.lat, winner.lon), result.destination)
        assertEquals(winner.placeId, result.destinationId)
        assertEquals(winner.name, result.destinationName)
        assertEquals(299.8927, result.distanceMeters, absoluteTolerance = 0.001)
    }

    @Test
    fun breaksScoreTiesByDistanceThenPlaceId() = runBlocking {
        val api = nearbyApi(origin) {
            listOf(place("a-farther", 0.002, 40.0), place("z-nearest", 0.001, 40.0), place("b-nearest", 0.001, 40.0))
        }

        val route = findQuietPlace(api, origin, score) { error("Unexpected offline lookup") }
        assertEquals("b-nearest", route?.destinationId)
    }

    @Test
    fun resolvesAgainstTheBundledLausannePlacesWhenOffline() = runBlocking {
        val lausanne = RoutePoint(46.5236, 6.6327)
        val places = decodeOfflinePlaces(File("../data/processed/places_lausanne.geojson").readText())
        val api = nearbyApi(lausanne) { error("Network unavailable") }

        val route = assertNotNull(findQuietPlace(api, lausanne, PersonalModelSession.restore(null)::score) { places })
        val destination = places.single { it.placeId == route.destinationId }
        assertEquals(destination.name, route.destinationName)
        assertTrue(route.distanceMeters in 0.0..300.0)
    }

    @Test
    fun fallsBackWhenTheNearbyLookupTimesOut() = runBlocking {
        val api = nearbyApi(origin) { awaitCancellation() }

        val route = findQuietPlace(api, origin, score) { listOf(place("offline", 0.001, 40.0)) }
        assertEquals("offline", route?.destinationId)
    }

    @Test
    fun respectsAnEmptyOnlineResultWithoutSuggestingOfflinePlaces() = runBlocking {
        val api = nearbyApi(origin) { emptyList() }

        assertNull(findQuietPlace(api, origin, score) { error("Unexpected offline lookup") })
    }

    private fun features(db: Double) = FeatureVector(db, db, emptyMap(), 100.0, 0.0, 0)

    private fun place(id: String, longitude: Double, db: Double) =
        Place(id, "Place $id", lat = origin.lat, lon = longitude, category = "library", prior = features(db))

    private fun nearbyApi(expectedOrigin: RoutePoint, response: suspend () -> List<Place>): PlacesApi =
        object : PlacesApi by FakePlacesApi() {
            override suspend fun nearby(lat: Double, lon: Double, radiusM: Int, openOnly: Boolean, at: String?): List<Place> {
                assertEquals(expectedOrigin, RoutePoint(lat, lon))
                assertEquals(300, radiusM)
                assertTrue(openOnly)
                assertNull(at)
                return response()
            }
        }
}
