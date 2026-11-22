package ch.ciuin.app.insight

import ch.ciuin.app.route.RoutePoint
import ch.ciuin.shared.model.Place
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceSearchTest {
    @Test
    fun matchesFoldedUnicodeAndRanksNameBeforeCategory() {
        val places = listOf(
            place("category", "La Terrasse", category = "cafe"),
            place("substring", "Decaf"),
            place("word", "Le Grand-Café"),
            place("prefix2", "Cafétéria Flon"),
            place("prefix1", "Café du Grütli"),
            place("wordAfterSubstring", "Bricaf Café"),
        )
        val index = PlaceIndex(places)
        assertEquals(
            listOf("prefix1", "prefix2", "wordAfterSubstring", "word", "substring", "category"),
            index.searchPlaces(" CAF ").map { it.placeId },
        )
        assertEquals(listOf("prefix1"), index.searchPlaces("du GRU\u0308").map { it.placeId })
        assertEquals(
            listOf("prefix1", "prefix2", "wordAfterSubstring", "word", "category"),
            index.searchPlaces("cafe").map { it.placeId },
        )
        assertEquals(emptyList(), index.searchPlaces("   "))
        assertEquals(emptyList(), index.searchPlaces("caf", limit = 0))
        assertEquals(emptyList(), searchPlaces(emptyList(), "caf"))
    }

    @Test
    fun breaksTiesByDistanceThenNameAndHandlesUnknownCoordinates() {
        val places = listOf(
            place("far", "Cafe A", lon = 179.8),
            place("invalid", "Cafe B", lon = Double.NaN),
            place("nearZ", "Cafe Z", lon = -179.999),
            place("nearC", "Café C", lon = -179.999),
        )
        val index = PlaceIndex(places)
        assertEquals(listOf("nearC", "nearZ"), index.searchPlaces("caf", RoutePoint(0.0, 179.999), 2).map { it.placeId })
        assertEquals(listOf("far", "invalid", "nearC", "nearZ"), index.searchPlaces("caf").map { it.placeId })
        assertEquals(listOf("far"), index.searchPlaces("caf", RoutePoint(91.0, 0.0), 1).map { it.placeId })
    }

    @Test
    fun reusesTheSameIndexForSuccessiveQueriesOnACityList() {
        val places = (1..3_000).map { place(it.toString(), "Shop $it") }
        val index = PlaceIndex(places + place("flon", "Cafétéria Flon"))
        for (query in listOf("c", "ca", "caf", "cafe", "cafeteria")) {
            assertEquals("flon", index.searchPlaces(query, limit = 1).single().placeId)
        }
    }

    private fun place(id: String, name: String, lon: Double = 0.0, category: String = "restaurant") =
        Place(id, name, 0.0, lon, category)
}
