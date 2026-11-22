package ch.ciuin.app.route

import ch.ciuin.shared.model.FeatureVector
import ch.ciuin.shared.model.Place
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QuietSpotTest {
    private val origin = RoutePoint(0.0, 179.9998)
    private val score: (FeatureVector) -> Double = { requireNotNull(it.dbMean) / 100.0 }

    @Test
    fun ranksOpenAndUnknownPlacesByScoreDistanceThenId() {
        val a = place("a", -179.9999, 30.0)
        val places = listOf(
            place("closed", 179.9999, 20.0).copy(openNow = false),
            place("no-features", 179.9998, 10.0).copy(prior = null),
            place("invalid-score", 179.9998, Double.NaN),
            place("near-explicit-open", 179.9998, 40.0).copy(openNow = true),
            place("further", -179.999, 30.0),
            place("z", -179.9999, 30.0),
            a,
        )

        val best = assertNotNull(quietSpotNear(places, origin, score))
        assertEquals(a, best.place)
        assertEquals(0.3, best.predictedOverwhelm)
        assertEquals(33.358, best.distanceMeters, 0.001)
        assertEquals("near-explicit-open", quietSpotNear(places, origin, score, 5.0)?.place?.placeId)
        assertNull(quietSpotNear(emptyList(), origin, score))
        assertNull(quietSpotNear(places, origin, score, -1.0))
    }

    @Test
    fun nearestCalmSpotChoosesTheClosestCalmPlace() {
        val nearby = place("nearby", 179.9999, 30.0)
        val calmer = place("calmer", -179.9999, 10.0)
        val loud = place("loud", 179.9998, 90.0)

        assertEquals(nearby, nearestCalmSpot(listOf(calmer, loud, nearby), origin, score)?.place)
        assertNull(nearestCalmSpot(listOf(loud), origin, score))
    }

    @Test
    fun buildsPreviewForAnyValidSelectedPlace() {
        val selected = place("chosen", -179.9999, 0.0).copy(openNow = false, prior = null)
        val route = assertNotNull(routeToPlace(origin, selected))
        assertEquals(RoutePoint(0.0, -179.9999), route.destination)
        assertEquals("chosen", route.destinationId)
        assertEquals(33.358, route.distanceMeters, 0.001)
        assertNull(routeToPlace(origin, selected.copy(lat = 91.0)))
        assertNull(routeToPlace(origin, selected.copy(name = "")))
    }

    private fun place(id: String, lon: Double, db: Double) =
        Place(id, id, 0.0, lon, "cafe", prior = FeatureVector(dbMean = db))
}
