package ch.ciuin.app.insight

import ch.ciuin.app.route.RoutePoint
import ch.ciuin.shared.model.Place
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaceDistanceTest {
    @Test
    fun suppliesUnitAndRoundedValueSeparately() {
        assertEquals(DistanceTokens("0", DistanceUnit.METRES), distanceTokens(0.0))
        assertEquals(DistanceTokens("999", DistanceUnit.METRES), distanceTokens(999.0))
        assertEquals(DistanceTokens("1.0", DistanceUnit.KILOMETRES), distanceTokens(1_000.0))
        assertEquals(DistanceTokens("1.3", DistanceUnit.KILOMETRES), distanceTokens(1_260.0))
        assertEquals(DistanceTokens("10.0", DistanceUnit.KILOMETRES), distanceTokens(9_975.0))
        assertEquals(1, walkingMinutes(0.0))
        assertEquals(1, walkingMinutes(78.0))
        assertEquals(2, walkingMinutes(156.0))
    }

    @Test
    fun returnsNearestTwoWithinSeventyFiveMetresAcrossDateLine() {
        val origin = RoutePoint(0.0, 179.9998)
        val places = listOf(
            place("outside", -179.9993),
            place("third", -179.9996),
            place("across", -179.9999),
            place("same", 179.9998),
            place("invalid", 181.0),
        )
        val nearby = nearestPlaces(places, origin)
        assertEquals(listOf("same", "across"), nearby.map { it.place.placeId })
        assertEquals(0.0, nearby.first().distanceMeters)
        assertEquals(33.358, nearby.last().distanceMeters, 0.001)
        assertEquals(listOf("same", "across", "third"), nearestPlaces(places, origin, limit = 10).map { it.place.placeId })
        assertEquals(listOf("same"), nearestPlaces(places, origin, radiusM = 0.0).map { it.place.placeId })
        assertTrue(nearestPlaces(places, RoutePoint(Double.NaN, 0.0)).isEmpty())
        assertTrue(nearestPlaces(emptyList(), origin).isEmpty())
    }

    @Test
    fun equivalentDateLineAndPoleCoordinatesAreZeroMetresApart() {
        val atDateLine = nearestPlaces(listOf(place("seam", -180.0)), RoutePoint(0.0, 180.0), radiusM = 0.0)
        assertEquals(0.0, atDateLine.single().distanceMeters)
        val pole = place("pole", 179.0).copy(lat = 90.0)
        assertEquals(0.0, nearestPlaces(listOf(pole), RoutePoint(90.0, -45.0), radiusM = 0.0).single().distanceMeters)
    }

    private fun place(id: String, lon: Double) = Place(id, id, 0.0, lon, "cafe")
}
