@file:OptIn(kotlin.time.ExperimentalTime::class)

package ch.ciuin.app.insight

import ch.ciuin.shared.model.Place
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class PlaceFactsTest {
    private val cafe = Place("osm:node/123456", "Café", 46.5, 6.6, "cafe")
    private val summerMonday = Instant.parse("2026-07-06T16:30:00Z")

    @Test
    fun returnsLabelsAndOnlyExplicitlyTaggedFacts() {
        val facts = placeFacts(cafe.copy(
            quietRoom = true, sensoryFriendly = "yes", wheelchair = "limited",
            osmTags = mapOf(
                "outdoor_seating" to "yes", "indoor_seating" to "no",
                "live_music" to "no", "air_conditioning" to "yes", "toilets:wheelchair" to "yes",
            ),
        ), summerMonday)
        assertEquals("Café", facts.categoryLabel)
        assertEquals("Pharmacy", categoryLabel("pharmacy"))
        assertEquals("Art Supply Shop", categoryLabel("art_supply_shop"))
        assertEquals(
            listOf(PlaceFact.QUIET_ROOM, PlaceFact.SENSORY_FRIENDLY, PlaceFact.OUTDOOR_SEATING,
                PlaceFact.NO_LIVE_MUSIC, PlaceFact.AIR_CONDITIONING, PlaceFact.ACCESSIBLE_TOILET, PlaceFact.WHEELCHAIR_LIMITED),
            facts.chips,
        )
        assertEquals(emptyList(), placeFacts(cafe.copy(osmTags = mapOf(
            "sensory_friendly" to "no", "air_conditioning" to "unknown", "toilets:wheelchair" to "limited",
        )), summerMonday).chips)
        assertEquals("https://www.openstreetmap.org/node/123456", facts.osmUrl)
        assertEquals("https://www.openstreetmap.org/relation/42", osmUrl("osm:relation/42"))
        assertEquals("https://www.openstreetmap.org/way/42", osmUrl("osm:way/42"))
        for (id in listOf("fake:1", "osm:node/0", "osm:node/12?x=", "osm:area/12")) assertNull(osmUrl(id))
    }

    @Test
    fun evaluatesSwissLocalTimeAcrossSeasonAndDayChanges() {
        val place = cafe.copy(osmTags = mapOf("opening_hours" to "Mo-Fr 08:00-12:00,13:30-19:00; Sa 10:00-24:00; Su off"))
        fun at(utc: String) = placeHours(place, Instant.parse(utc))

        assertEquals(PlaceHours(OpenStatus.OPEN, "19:00"), placeHours(place, summerMonday))
        assertEquals(PlaceHours(OpenStatus.OPEN, "19:00"), at("2026-01-05T17:30:00Z"))
        assertEquals(PlaceHours(OpenStatus.CLOSED, "19:00"), at("2026-07-06T17:00:00Z"))
        assertEquals(PlaceHours(OpenStatus.OPEN, "12:00"), at("2026-07-06T09:30:00Z"))
        assertEquals(PlaceHours(OpenStatus.CLOSED, "19:00"), at("2026-07-06T10:30:00Z"))
        assertEquals(PlaceHours(OpenStatus.OPEN, "24:00"), at("2026-07-11T21:30:00Z"))
        assertEquals(PlaceHours(OpenStatus.CLOSED), at("2026-07-11T22:30:00Z"))
    }

    @Test
    fun fallsBackToServerStatusForRichOrMalformedSchedules() {
        for (value in listOf("", "by appointment", "Mo 10:00+", "PH off", "Fr 19:00-01:00", "Mo 09:60-19:00",
            "Mo 09:00-17:00; Mo 13:00-14:00")) {
            val place = cafe.copy(osmTags = mapOf("opening_hours" to value))
            assertEquals(PlaceHours(OpenStatus.UNKNOWN), placeHours(place, summerMonday), value)
            assertEquals(PlaceHours(OpenStatus.OPEN), placeHours(place.copy(openNow = true), summerMonday), value)
            assertEquals(PlaceHours(OpenStatus.CLOSED), placeHours(place.copy(openNow = false), summerMonday), value)
        }
        assertEquals(PlaceHours(OpenStatus.UNKNOWN), placeHours(cafe, summerMonday))
        for (value in listOf("24/7", "Mo-Su 00:00-24:00")) {
            assertEquals(PlaceHours(OpenStatus.OPEN), placeHours(cafe.copy(osmTags = mapOf("opening_hours" to value)), summerMonday))
        }
    }
}
