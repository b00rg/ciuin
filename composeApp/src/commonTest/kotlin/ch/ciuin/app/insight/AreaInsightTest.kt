package ch.ciuin.app.insight

import ch.ciuin.app.Level
import ch.ciuin.app.heat.AccessShares
import ch.ciuin.app.heat.ViewBounds
import ch.ciuin.shared.api.NoisePeriod
import ch.ciuin.shared.model.FeatureVector
import ch.ciuin.shared.model.HeatPoint
import ch.ciuin.shared.model.MeasuredAggregate
import ch.ciuin.shared.model.Place
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AreaInsightTest {
    @Test
    fun visibleAreaKeepsTypedLevelsCountsAndSourceMeans() {
        val inside = listOf(
            place("a", wheelchair = "designated").copy(measured = measured(lux = 40.0, crowd = 0)),
            place("b", wheelchair = "limited").copy(measured = measured(lux = 100.0)),
            place("c", wheelchair = "no").copy(prior = FeatureVector(luxMean = 1_000.0, nearbyDevices = 50)),
            place("d", wheelchair = "unknown"),
        )
        val places = inside + place("out", lon = 0.0, wheelchair = "yes").copy(measured = measured(1_000.0, 90))
        val grid = listOf(
            HeatPoint(0.0, -179.0, 50.0, null),
            HeatPoint(0.0, 179.0, 70.0, 60.0),
            HeatPoint(0.0, 0.0, 90.0, 90.0),
        )
        val scores = mapOf("a" to 0.2, "c" to 0.8, "out" to 1.0)
        val summary = areaInsight(places, grid, ViewBounds(0.99, 0.49, 0.01, 0.51), scores)

        assertEquals(RatedMean(0.5, Level.MEDIUM), summary.personal.overwhelm)
        assertEquals(Level.LOW, summary.personal.lightLevel)
        assertEquals(Level.LOW, summary.personal.crowdLevel)
        assertEquals(67.443, assertNotNull(summary.personal.meanDb), 0.001)
        assertEquals(Level.HIGH, summary.sound.noise?.level)
        assertEquals(67.033, assertNotNull(summary.sound.roadMeanDb), 0.001)
        assertEquals(60.0, summary.sound.railMeanDb)
        assertEquals(MeasuredInsight.Data(70.0, Level.LOW, 2), summary.light)
        assertEquals(MeasuredInsight.Data(0.0, Level.LOW, 1), summary.crowd)
        assertEquals(AccessInsight(AccessShares(1.0 / 3, 1.0 / 3, 1.0 / 3), 3), summary.access)
        assertEquals(4, areaInsight(places, grid).access.taggedPlaces)
        assertEquals(summary, areaInsight(places, grid, ViewBounds(-0.01, 0.49, 0.01, 0.51), scores))
    }

    @Test
    fun emptyAreaAndMissingSourcesHaveExplicitStates() {
        val empty = areaInsight(emptyList(), emptyList())
        assertEquals(PersonalInsight(null, null, null, null), empty.personal)
        assertEquals(SoundInsight(null, null, null), empty.sound)
        assertEquals(MeasuredInsight.NoData, empty.light)
        assertEquals(MeasuredInsight.NoData, empty.crowd)
        assertEquals(AccessInsight(null, 0), empty.access)

        val totalOnly = areaInsight(emptyList(), listOf(HeatPoint(0.0, 0.0, null, null, 46.0))).sound
        assertEquals(RatedMean(46.0, Level.LOW), totalOnly.noise)
        assertNull(totalOnly.roadMeanDb)
        assertNull(totalOnly.railMeanDb)
    }

    @Test
    fun nightUsesOnlyNightBaselinesAndTheSuppliedGrid() {
        val place = place("a").copy(
            baselineNoiseDb = 60.0, baselineNoiseNightDb = 40.0,
            measured = MeasuredAggregate(1, FeatureVector(dbMean = 80.0)),
        )
        val day = areaInsight(listOf(place), emptyList())
        val night = areaInsight(listOf(place, place("b").copy(baselineNoiseDb = 81.0)), emptyList(), noisePeriod = NoisePeriod.NIGHT)
        assertEquals(RatedMean(80.0, Level.HIGH), day.sound.noise)
        assertEquals(RatedMean(40.0, Level.LOW), night.sound.noise)
    }

    private fun place(id: String, lon: Double = 179.0, wheelchair: String? = null) =
        Place(id, id, 0.0, lon, "cafe", wheelchair = wheelchair)

    private fun measured(lux: Double, crowd: Int? = null) =
        MeasuredAggregate(3, FeatureVector(luxMean = lux, nearbyDevices = crowd))
}
