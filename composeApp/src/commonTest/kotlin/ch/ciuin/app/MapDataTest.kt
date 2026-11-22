package ch.ciuin.app

import ch.ciuin.app.insight.MeasuredInsight
import ch.ciuin.app.insight.areaInsight
import ch.ciuin.shared.api.BBox
import ch.ciuin.shared.api.FakePlacesApi
import ch.ciuin.shared.api.FallbackPlacesApi
import ch.ciuin.shared.api.NoisePeriod
import ch.ciuin.shared.api.PlacesApi
import ch.ciuin.shared.model.HeatPoint
import ch.ciuin.shared.model.Place
import ch.ciuin.shared.model.heatGridToGeoJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapDataTest {
    @Test
    fun showsMeasuredCellsEvenWhenThereIsNoListedPlace() = runTest {
        val primary = object : PlacesApi by FakePlacesApi() {
            override suspend fun heatmap(bbox: BBox, dim: String, period: NoisePeriod?) = Json.parseToJsonElement(
                """{
                    "type":"FeatureCollection", "cell_m":50, "features":[
                        {"geometry":{"type":"Point","coordinates":[6.63,46.52]},"properties":{"value":320,"n":3}},
                        {"geometry":{"type":"Point","coordinates":[6.64,46.53]},"properties":{"value":720,"n":1}}
                    ]
                }""",
            ).jsonObject
        }
        val data = MapData()

        data.refreshReadings(primary, ReadingLayer.Light)
        val grid = data.readingGrids.getValue(ReadingLayer.Light)
        val insight = areaInsight(emptyList(), emptyList(), lightGrid = grid.samples)

        assertEquals(50.0, grid.resolutionMeters)
        assertEquals(MeasuredInsight.Data(420.0, Level.MEDIUM, 2), insight.light)
    }

    @Test
    fun keepsLivePlacesAndGridWhenTheBackendGoesOffline() = runTest {
        val place = Place("osm:node/1", "Café", 46.52, 6.63, "cafe")
        val point = HeatPoint(46.52, 6.63, roadDb = 56.0, railDb = null)
        val fake = FakePlacesApi()
        var connected = false
        val primary = object : PlacesApi by fake {
            override suspend fun places(bbox: BBox, at: String?) =
                if (connected) listOf(place) else error("Offline")

            override suspend fun heatmap(bbox: BBox, dim: String, period: NoisePeriod?) =
                if (connected) heatGridToGeoJson(listOf(point), resM = 50) else error("Offline")
        }
        val api = FallbackPlacesApi(primary, fake)
        val data = MapData()

        data.refreshPlaces(api)
        data.refreshNoise(api, NoisePeriod.DAY)
        assertTrue(data.usingSamplePlaces)
        assertTrue(data.grids.isEmpty())

        connected = true
        data.refreshPlaces(api)
        data.refreshNoise(api, NoisePeriod.DAY)
        assertFalse(data.offline)

        connected = false
        data.refreshPlaces(api)
        data.refreshNoise(api, NoisePeriod.DAY)
        assertTrue(data.offline)
        assertFalse(data.usingSamplePlaces)
        assertEquals(listOf(place), data.places)
        assertEquals(listOf(point), data.grids[NoisePeriod.DAY]?.points)
    }
}
