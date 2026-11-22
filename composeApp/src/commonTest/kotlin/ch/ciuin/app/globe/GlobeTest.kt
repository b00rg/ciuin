package ch.ciuin.app.globe

import ch.ciuin.shared.model.Coverage
import ch.ciuin.shared.model.CoverageCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobeTest {
    @Test
    fun landMaskRecognisesContinentsAndOcean() {
        assertTrue(isLand(LAUSANNE_LAT, LAUSANNE_LON))
        assertTrue(isLand(23.4, 13.0))
        assertFalse(isLand(0.0, -150.0))
        assertTrue(isLand(-85.0, 0.0))
        assertTrue(isLand(-90.0, 180.0))
        assertFalse(isLand(90.0, 0.0))
        assertEquals(isLand(-85.0, -180.0), isLand(-85.0, 180.0))
        assertEquals(isLand(23.4, 13.0), isLand(23.4, 373.0))
        assertFalse(isLand(Double.NaN, 0.0))
        assertFalse(isLand(0.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun dotsUseCellCentresAndCanBeCoarsened() {
        val dots = globeDots()
        assertTrue(dots.size in 3_000..7_000)
        assertEquals(dots.size, dots.toSet().size)
        assertTrue(GlobePoint(47.0, 7.0) in dots)
        assertFalse(GlobePoint(1.0, -149.0) in dots)
        assertTrue(dots.all { it.lat in -89.0..89.0 && it.lon in -179.0..179.0 && isLand(it.lat, it.lon) })
        assertTrue(globeDots(4.0).size < dots.size)
        assertFailsWith<IllegalArgumentException> { globeDots(0.0) }
    }

    @Test
    fun projectsTheFrontHemisphereAndHandlesPolesAndTheDateLine() {
        val projection = GlobeProjection(GlobePoint(0.0, 0.0))
        assertEquals(GlobePosition(0.0, 0.0, true), projection.project(GlobePoint(0.0, 0.0)))
        assertEquals(GlobePosition(1.0, 0.0, true), projection.project(GlobePoint(0.0, 90.0)))
        assertEquals(GlobePosition(0.0, 1.0, true), projection.project(GlobePoint(90.0, 0.0)))
        assertFalse(projection.project(GlobePoint(0.0, 180.0)).visible)
        val nearSeam = GlobeProjection(GlobePoint(0.0, 179.0)).project(GlobePoint(0.0, -179.0))
        assertTrue(nearSeam.visible)
        assertEquals(0.034899, nearSeam.x, 1e-6)
        val polar = GlobeProjection(GlobePoint(90.0, 0.0))
        assertTrue(polar.project(GlobePoint(45.0, 180.0)).visible)
        assertFalse(polar.project(GlobePoint(-45.0, 0.0)).visible)
    }

    @Test
    fun invertsDragWithinTheDiscAndStopsAtThePoles() {
        val projection = GlobeProjection(GlobePoint(20.0, -175.0))
        val centre = projection.drag(0.2, -0.4)
        val position = projection.project(centre)
        assertTrue(centre.lon > 0.0)
        assertEquals(-0.2, position.x, 1e-10)
        assertEquals(0.4, position.y, 1e-10)
        assertEquals(GlobePoint(90.0, -175.0), projection.drag(0.0, -4.0))
        assertEquals(GlobePoint(-90.0, 15.0), GlobeProjection(GlobePoint(-30.0, 15.0)).drag(0.0, 4.0))
        assertEquals(projection.centre, projection.drag(0.0, 0.0))
        assertEquals(projection.centre, projection.drag(Double.NaN, 0.0))
        val longDrag = projection.project(projection.drag(Double.MAX_VALUE, Double.MAX_VALUE))
        assertEquals(1.0, longDrag.x * longDrag.x + longDrag.y * longDrag.y, 1e-10)
    }

    @Test
    fun scalesOnlyMeasuredCellsLogarithmically() {
        val coverage = Coverage(2.0, listOf(
            CoverageCell(47.0, 7.0, measurements = 3),
            CoverageCell(1.0, 1.0, wheelchair = 10),
            CoverageCell(31.0, 11.0, measurements = 15),
        ))
        assertEquals(
            listOf(GlobeSpike(GlobePoint(47.0, 7.0), 0.5, 3), GlobeSpike(GlobePoint(31.0, 11.0), 1.0, 15)),
            globeSpikes(coverage),
        )
        assertTrue(globeSpikes(Coverage(2.0, emptyList())).isEmpty())
        assertTrue(globeSpikes(Coverage(2.0, listOf(CoverageCell(0.0, 0.0)))).isEmpty())
    }
}
