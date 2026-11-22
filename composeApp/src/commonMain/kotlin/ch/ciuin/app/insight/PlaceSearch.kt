package ch.ciuin.app.insight

import ch.ciuin.app.route.RoutePoint
import ch.ciuin.app.route.distanceMeters
import ch.ciuin.shared.model.Place
import ch.ciuin.shared.text.decomposeUnicode

/** Keep one index per place list so typing only folds the query and ranks matching entries. */
class PlaceIndex(places: List<Place>) {
    private val entries = places.map(::Entry)

    fun searchPlaces(query: String, origin: RoutePoint? = null, limit: Int = 10): List<Place> {
        val needle = fold(query)
        if (needle.isEmpty() || limit <= 0) return emptyList()
        val start = origin?.takeIf { it.isValid }
        return entries.asSequence().mapNotNull { entry ->
            val rank = entry.rank(needle) ?: return@mapNotNull null
            val distance = if (start != null && entry.point.isValid) {
                distanceMeters(start, entry.point)
            } else Double.POSITIVE_INFINITY
            Match(entry, rank, distance)
        }.sortedWith(compareBy({ it.rank }, { it.distance }, { it.entry.name }, { it.entry.place.name }, { it.entry.place.placeId }))
            .take(limit).map { it.entry.place }.toList()
    }

    private class Entry(val place: Place) {
        val name = fold(place.name)
        val point = RoutePoint(place.lat, place.lon)
        val category = fold(categoryLabel(place.category))
        val categoryCode = fold(place.category)

        fun rank(query: String): Int? {
            val first = name.indexOf(query)
            if (first == 0) return 0
            if (first > 0) {
                var match = first
                while (match >= 0) {
                    if (name[match - 1] == ' ') return 1
                    match = name.indexOf(query, match + 1)
                }
                return 2
            }
            return if (query in category || query in categoryCode) 3 else null
        }
    }

    private data class Match(val entry: Entry, val rank: Int, val distance: Double)
}

fun searchPlaces(places: List<Place>, query: String, origin: RoutePoint? = null, limit: Int = 10): List<Place> =
    if (query.isBlank() || limit <= 0) emptyList() else PlaceIndex(places).searchPlaces(query, origin, limit)

private val separators = Regex("[^\\p{L}\\p{N}]+")

/** A space marks word starts even in names with apostrophes or hyphens. */
private fun fold(value: String): String = decomposeUnicode(value.lowercase())
    .filterNot { it.category in combiningMarks }
    .replace("œ", "oe").replace("æ", "ae").replace("ß", "ss")
    .replace(separators, " ").trim()

private val combiningMarks = setOf(CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK)
