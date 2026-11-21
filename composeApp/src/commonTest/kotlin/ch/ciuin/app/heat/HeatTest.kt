package ch.ciuin.app.heat

import ch.ciuin.app.Category
import ch.ciuin.app.Level
import ch.ciuin.app.personalFeatures
import ch.ciuin.shared.model.FeatureVector
import ch.ciuin.shared.model.HeatPoint
import ch.ciuin.shared.model.MeasuredAggregate
import ch.ciuin.shared.model.Place
import ch.ciuin.shared.model.SoundClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeatTest {
    private fun place(id: String, lat: Double, lon: Double, wheelchair: String? = null, baseline: Double? = null, top: SoundClass? = null) =
        Place(
            id, id, lat, lon, "cafe", wheelchair = wheelchair, baselineNoiseDb = baseline,
            measured = top?.let { t ->
                MeasuredAggregate(1, FeatureVector(60.0, 70.0, SoundClass.entries.associate { it.label to if (it == t) 1.0 else 0.0 }, 300.0, 0.0, 5))
            },
        )

    @Test
    fun aggregateAveragesWithinCellsAndCoarserLevelsMerge() {
        val samples = listOf(HeatSample(0.1, 0.1, 40.0), HeatSample(0.1001, 0.1001, 60.0), HeatSample(0.9, 0.9, 70.0))
        val fine = aggregate(samples, level = 10)
        assertEquals(2, fine.size)
        assertEquals(50.0, fine.first { it.count == 2 }.mean)
        // At level 0 the whole world is one cell: the overall average.
        val world = aggregate(samples, level = 0).single()
        assertEquals((40.0 + 60.0 + 70.0) / 3, world.mean, 1e-9)
    }

    @Test
    fun cellLevelFollowsZoomButNotFinerThanData() {
        assertEquals(15, cellLevel(zoom = 12.4, lat = 46.5, dataResM = 50.0))
        // 50 m data at Lausanne's latitude caps cells at level 19 (~53 m).
        assertEquals(19, cellLevel(zoom = 19.0, lat = 46.5, dataResM = 50.0))
        assertEquals(18, cellLevel(zoom = 19.0, lat = 46.5, dataResM = 100.0))
    }

    @Test
    fun soundSummarySplitsRoadAndRailByEnergyAndGroupsSoundTypes() {
        val grid = listOf(HeatPoint(46.52, 6.63, roadDb = 60.0, railDb = 60.0))
        val places = listOf(place("a", 46.52, 6.63, top = SoundClass.SPEECH_CROWD), place("b", 46.52, 6.63, top = SoundClass.SIREN))
        val s = summarise(Category.SOUND, places, grid, bounds = null)

        val baseline = s.sections.first { it.heading.startsWith("Baseline") }.shares
        assertEquals(0.5, baseline.first { it.label == "Rail" }.fraction, 1e-9)

        val measured = s.sections.first { it.heading.startsWith("Measured") }.shares.associate { it.label to it.fraction }
        assertEquals(0.5, measured["Crowds & voices"])
        assertEquals(0.5, measured["Sirens & alarms"])
    }

    @Test
    fun boundsLimitTheSummaryToTheVisibleArea() {
        val places = listOf(place("in", 46.52, 6.63, wheelchair = "no"), place("out", 47.5, 8.5, wheelchair = "yes"))
        val view = ViewBounds(
            ch.ciuin.app.map.lonToX(6.62), ch.ciuin.app.map.latToY(46.53),
            ch.ciuin.app.map.lonToX(6.64), ch.ciuin.app.map.latToY(46.51),
        )
        val s = summarise(Category.ACCESS, places, emptyList(), view)
        assertEquals(1, s.sampleCount)
        assertEquals(Level.HIGH, Category.ACCESS.levelOf(s.mean!!))
    }

    @Test
    fun hexBinsGroupNearbyPointsAndCentreRoundTrips() {
        val r = hexRadius(15)
        val hexes = aggregateHex(
            listOf(HeatSample(0.5, 0.5, 40.0), HeatSample(0.5 + r * 0.1, 0.5, 60.0), HeatSample(0.5 + r * 4, 0.5, 70.0)),
            r,
        )
        assertEquals(2, hexes.size)
        val pair = hexes.first { it.count == 2 }
        assertEquals(50.0, pair.mean)
        // A hexagon's own centre falls back into that same hexagon.
        assertEquals(hexAt(0.5, 0.5, r), hexAt(pair.x, pair.y, r))
        // Every point lies within one circumradius of its hexagon's centre.
        val dx = 0.5 - pair.x
        val dy = 0.5 - pair.y
        assertTrue(kotlin.math.sqrt(dx * dx + dy * dy) <= r)
    }

    @Test
    fun soundAveragesEnergyNotDecibels() {
        // 50 dB + 70 dB: energy mean ≈ 67 dB (a plain mean would say 60).
        assertEquals(67.0, Averaging.DECIBELS.mean(listOf(50.0, 70.0))!!, 0.1)
        val cell = aggregate(listOf(HeatSample(0.1, 0.1, 50.0), HeatSample(0.1, 0.1, 70.0)), 10, Averaging.of(Category.SOUND)).single()
        assertEquals(67.0, cell.mean, 0.1)
        assertEquals(60.0, Averaging.of(Category.CROWD).mean(listOf(50.0, 70.0)))
    }

    @Test
    fun noDataGivesNullMean() {
        val s = summarise(Category.LIGHT, listOf(place("a", 46.52, 6.63, baseline = 50.0)), emptyList(), null)
        assertNull(s.mean)
        assertTrue(s.sections.isEmpty())
    }

    @Test
    fun feedbackFeaturesPreferMeasurementsThenEstimates() {
        val estimate = FeatureVector(40.0, 45.0, emptyMap(), 100.0, 0.0, 1)
        val measured = place("measured", 46.52, 6.63, top = SoundClass.SPEECH_CROWD).copy(prior = estimate)
        val estimated = place("estimated", 46.52, 6.64).copy(prior = estimate)

        assertEquals(measured.measured?.features, measured.personalFeatures)
        assertEquals(estimate, estimated.personalFeatures)
        assertNull(place("missing", 46.52, 6.65, baseline = 55.0).personalFeatures)
    }

    @Test
    fun personalLayerUsesOnlyModelScores() {
        val places = listOf(place("scored", 46.52, 6.63, baseline = 60.0), place("unscored", 46.52, 6.64, baseline = 75.0))
        val grid = listOf(HeatPoint(46.52, 6.63, roadDb = null, railDb = null, totalDb = 55.0))
        val scores = mapOf("scored" to 0.63)

        assertEquals(listOf(0.63), samplesFor(Category.PERSONAL, places, grid, scores).map { it.value })
        assertEquals(0.63, summarise(Category.PERSONAL, places, grid, bounds = null, personalScores = scores).mean)
        assertEquals(listOf(55.0, 60.0, 75.0), samplesFor(Category.SOUND, places, grid, scores).map { it.value })
    }
}
