@file:OptIn(kotlin.time.ExperimentalTime::class)


package ch.ciuin.app.insight

import ch.ciuin.shared.model.Place
import kotlin.time.Clock
import kotlin.time.Instant

enum class PlaceFact {
    QUIET_ROOM, SENSORY_FRIENDLY, OUTDOOR_SEATING, INDOOR_SEATING,
    LIVE_MUSIC, NO_LIVE_MUSIC, AIR_CONDITIONING, ACCESSIBLE_TOILET, NO_SMOKING,
    WHEELCHAIR_ACCESSIBLE, WHEELCHAIR_LIMITED, WHEELCHAIR_NOT_ACCESSIBLE,
}

data class PlaceFacts(
    val categoryLabel: String,
    val chips: List<PlaceFact>,
    val hours: PlaceHours,
    val osmUrl: String?,
)

fun placeFacts(place: Place, at: Instant = Clock.System.now()): PlaceFacts =
    PlaceFacts(categoryLabel(place.category), placeFactChips(place), placeHours(place, at), osmUrl(place.placeId))

fun categoryLabel(category: String): String = when (val value = category.trim().lowercase()) {
    "cafe" -> "Café"
    "bbq" -> "Barbecue"
    "clothes" -> "Clothing Shop"
    "convenience" -> "Convenience Store"
    "doctors" -> "Doctor's Office"
    "hairdresser" -> "Hair Salon"
    "place_of_worship" -> "Place of Worship"
    else -> value.split('_', ' ').filter(String::isNotEmpty).joinToString(" ") { word ->
        word.replaceFirstChar(Char::titlecase)
    }
}

private fun placeFactChips(place: Place): List<PlaceFact> = buildList {
    val tags = place.osmTags
    if (place.quietRoom || tags["quiet_room"] == "yes") add(PlaceFact.QUIET_ROOM)
    if ((place.sensoryFriendly ?: tags["sensory_friendly"]) == "yes") add(PlaceFact.SENSORY_FRIENDLY)
    if (tags["outdoor_seating"] == "yes") add(PlaceFact.OUTDOOR_SEATING)
    if (tags["indoor_seating"] == "yes") add(PlaceFact.INDOOR_SEATING)
    when (tags["live_music"]) {
        "yes" -> add(PlaceFact.LIVE_MUSIC)
        "no" -> add(PlaceFact.NO_LIVE_MUSIC)
    }
    if (tags["air_conditioning"] == "yes") add(PlaceFact.AIR_CONDITIONING)
    if (tags["toilets:wheelchair"] == "yes") add(PlaceFact.ACCESSIBLE_TOILET)
    if (tags["smoking"] == "no") add(PlaceFact.NO_SMOKING)
    when (place.wheelchair ?: tags["wheelchair"]) {
        "yes", "designated" -> add(PlaceFact.WHEELCHAIR_ACCESSIBLE)
        "limited" -> add(PlaceFact.WHEELCHAIR_LIMITED)
        "no" -> add(PlaceFact.WHEELCHAIR_NOT_ACCESSIBLE)
    }
}

private val osmId = Regex("osm:((?:node|way|relation)/[1-9][0-9]*)")

/** Only OSM objects have a link that can take the user to the original map entry. */
fun osmUrl(placeId: String): String? = osmId.matchEntire(placeId)?.groupValues?.get(1)
    ?.let { "https://www.openstreetmap.org/$it" }
