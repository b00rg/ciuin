package ch.ciuin.app

import ch.ciuin.shared.api.BBox
import ch.ciuin.shared.api.FakePlacesApi
import ch.ciuin.shared.api.FallbackPlacesApi
import ch.ciuin.shared.api.PlacesApi
import ch.ciuin.shared.model.Place
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class BundledPlacesApiTest {
    @Test
    fun readsRealPlacesFromThePackagedFileWhenTheBackendIsUnavailable(): Unit = runBlocking {
        val unavailable = object : PlacesApi by FakePlacesApi() {
            override suspend fun places(bbox: BBox, at: String?): List<Place> = error("Backend unavailable")
        }
        val api = FallbackPlacesApi(unavailable, BundledPlacesApi(::readBundledPlaces))

        val result = api.readPlaces(BBox.LAUSANNE)

        assertFalse(result.fromPrimary)
        assertFalse(result.fromSample)
        val park = assertNotNull(result.data.firstOrNull { it.placeId == "osm:way/330269687" })
        assertEquals("Parc de Mon-Repos", park.name)
        assertNotNull(park.baselineNoiseDb)
        assertNotNull(park.baselineNoiseNightDb)
    }
}
