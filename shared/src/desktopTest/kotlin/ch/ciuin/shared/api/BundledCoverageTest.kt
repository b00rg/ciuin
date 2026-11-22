package ch.ciuin.shared.api

import ch.ciuin.shared.model.Coverage
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundledCoverageTest {
    @Test
    fun fakeAndFallbackServeTheBundledCoverageForEachTier() = runTest {
        val fake = FakePlacesApi()
        val offline = object : PlacesApi by fake {
            override suspend fun coverage(resDeg: Double): Coverage = error("Offline")
        }
        val fallback = FallbackPlacesApi(offline, fake)
        for (degrees in listOf(1, 2)) {
            val file = File("../data/processed/coverage_${degrees}deg.json")
            val expected = ContractJson.decodeFromString<Coverage>(file.readText())
            assertTrue(expected.cells.isNotEmpty())
            assertEquals(expected, fake.coverage(degrees.toDouble()))
            assertEquals(expected, fallback.coverage(degrees.toDouble()))
        }
        assertEquals(Coverage(0.5, emptyList()), fallback.coverage(0.5))
    }
}
