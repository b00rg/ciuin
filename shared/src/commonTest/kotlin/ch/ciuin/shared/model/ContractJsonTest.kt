package ch.ciuin.shared.model

import ch.ciuin.shared.api.FakePlacesApi
import ch.ciuin.shared.api.BBox
import ch.ciuin.shared.api.ContractJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checks the Kotlin data classes against the JSON examples in Claude/todo.md section 0.
 * If one of these fails, the Kotlin side and the documented contract have drifted apart.
 */
class ContractJsonTest {
    // The production config, but strict on unknown keys so a renamed field fails here.
    private val json = Json(from = ContractJson) { ignoreUnknownKeys = false }

    private val featureJson = """
        {
          "db_mean": 62.4, "db_peak": 78.1,
          "sound_classes": {"speech_crowd": 0.71, "music": 0.12, "traffic": 0.08, "siren": 0.0, "construction": 0.0, "alarm_beep": 0.0, "dog": 0.0, "machinery_hum": 0.03, "other": 0.06},
          "lux_mean": 340, "flicker_index": 0.04,
          "people_count": 7
        }
    """.trimIndent()

    @Test
    fun featureVectorMatchesContract() {
        val f = json.decodeFromString<FeatureVector>(featureJson)
        assertEquals(62.4, f.dbMean)
        assertEquals(78.1, f.dbPeak)
        assertEquals(0.71, f.sound(SoundClass.SPEECH_CROWD))
        assertEquals(0.03, f.sound(SoundClass.MACHINERY_HUM))
        // Exactly the 9 classes, no strays like "quiet".
        assertEquals(SoundClass.entries.map { it.label }.toSet(), f.soundClasses.keys)
        assertEquals(1.0, f.soundClasses.values.sum(), 1e-9)
        assertEquals(340.0, f.luxMean)
        assertEquals(0.04, f.flickerIndex)
        assertEquals(7, f.peopleCount)
        assertRoundTrip(featureJson, f)
    }

    @Test
    fun soundClassOrderMatchesModelOutput() {
        // Order = model output index (todo.md B1). Changing this breaks every exported model.
        assertEquals(
            listOf("speech_crowd", "music", "traffic", "siren", "construction", "alarm_beep", "dog", "machinery_hum", "other"),
            SoundClass.entries.map { it.label },
        )
        assertEquals(SoundClass.COUNT, SoundClass.entries.size)
        assertEquals(SoundClass.ALARM_BEEP, SoundClass.fromLabel("alarm_beep"))
        assertNull(SoundClass.fromLabel("quiet"))
    }

    @Test
    fun measurementMatchesContract() {
        val src = """
            {
              "place_id": "osm:node/123456",
              "lat": 46.5191, "lon": 6.5668,
              "timestamp": "2026-11-21T14:05:00Z",
              "duration_s": 30,
              "features": $featureJson,
              "flicker_hz": 100,
              "model_variant": "audio-student-s",
              "device": "pixel-7"
            }
        """.trimIndent()
        val m = json.decodeFromString<Measurement>(src)
        assertEquals("osm:node/123456", m.placeId)
        assertEquals(30, m.durationS)
        assertEquals(100.0, m.flickerHz)
        assertEquals("audio-student-s", m.modelVariant)
        assertEquals(7, m.features.peopleCount)
        assertRoundTrip(src, m)
    }

    @Test
    fun placeMatchesContract() {
        val src = """
            {
              "place_id": "osm:node/123456", "name": "Café X", "lat": 46.5191, "lon": 6.5668,
              "category": "cafe",
              "wheelchair": "yes", "sensory_friendly": null, "quiet_room": false,
              "baseline_noise_db": 58, "baseline_source": "sonbase",
              "measured": {"n": 4, "features": $featureJson},
              "score": {"noise": 0.4, "light": 0.8, "flicker": 0.9, "crowd": 0.6, "access": 1.0}
            }
        """.trimIndent()
        val p = json.decodeFromString<Place>(src)
        assertEquals("Café X", p.name)
        assertNull(p.sensoryFriendly)
        assertEquals(58.0, p.baselineNoiseDb)
        assertEquals("sonbase", p.baselineSource)
        assertEquals(4, p.measured?.n)
        assertEquals(0.9, p.score?.flicker)
        assertRoundTrip(src, p)
    }

    @Test
    fun placeOutsideNoiseRegionDecodesWithNulls() {
        // A place outside Europe: no baseline noise, never measured, no score yet.
        val p = json.decodeFromString<Place>(
            """{"place_id": "osm:node/1", "name": "X", "lat": 40.7, "lon": -74.0, "category": "cafe",
               "baseline_noise_db": null, "baseline_source": null, "measured": null, "score": null}"""
        )
        assertNull(p.baselineNoiseDb)
        assertNull(p.baselineSource)
        assertNull(p.measured)
    }

    @Test
    fun trainingExampleMatchesContract() {
        val src = """{"features": $featureJson, "label": 1, "timestamp": "2026-11-21T14:05:00Z"}"""
        val e = json.decodeFromString<TrainingExample>(src)
        assertEquals(1, e.label)
        assertRoundTrip(src, e)
    }

    @Test
    fun coverageMatchesContract() {
        val src = """
            {
              "res_deg": 2.0,
              "cells": [
                {"lat": 47.0, "lon": 7.0, "wheelchair": 18342, "sensory_friendly": 3, "noise_source": "sonbase", "measurements": 12}
              ]
            }
        """.trimIndent()
        val c = json.decodeFromString<Coverage>(src)
        assertEquals(2.0, c.resDeg)
        val cell = c.cells.single()
        assertEquals(18342, cell.wheelchair)
        assertEquals(3, cell.sensoryFriendly)
        assertEquals("sonbase", cell.noiseSource)
        assertEquals(12, cell.measurements)
        assertRoundTrip(src, c)
    }

    @Test
    fun fakeApiReturnsFiveLausannePlaces() = runTest {
        val places = FakePlacesApi().places(BBox.LAUSANNE)
        assertEquals(5, places.size)
        assertTrue(places.all { it.lat in 46.50..46.54 && it.lon in 6.58..6.67 })
    }

    /** Encoding then decoding gives the same JSON as the documented example (numbers compared as values). */
    private inline fun <reified T> assertRoundTrip(src: String, value: T) {
        val expected = normalise(json.parseToJsonElement(src).jsonObject)
        val actual = normalise(json.parseToJsonElement(json.encodeToString(value)).jsonObject)
        assertEquals(expected, actual)
    }

    @Test
    fun encodingAlwaysIncludesDefaultedFields() {
        val p = Place("osm:node/1", "X", 46.5, 6.6, "cafe")
        val keys = json.parseToJsonElement(json.encodeToString(p)).jsonObject.keys
        assertTrue("quiet_room" in keys && "sensory_friendly" in keys && "baseline_source" in keys, "missing keys: $keys")
    }

    /** Re-parses numbers as doubles so 340 and 340.0 compare equal; treats null and absent as the same. */
    private fun normalise(o: JsonObject): Map<String, Any?> = o.entries
        .filter { it.value.toString() != "null" }
        .associate { (k, v) -> k to normaliseElement(v) }

    private fun normaliseElement(e: kotlinx.serialization.json.JsonElement): Any? = when (e) {
        is JsonObject -> normalise(e)
        is kotlinx.serialization.json.JsonArray -> e.map { normaliseElement(it) }
        is kotlinx.serialization.json.JsonPrimitive ->
            if (e.isString) e.content else e.content.toDoubleOrNull() ?: e.content
    }
}
