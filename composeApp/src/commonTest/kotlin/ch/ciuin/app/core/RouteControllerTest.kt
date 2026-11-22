package ch.ciuin.app.core

import ch.ciuin.app.route.QuietPlaceRoute
import ch.ciuin.app.route.RoutePoint
import ch.ciuin.app.route.WalkingRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RouteControllerTest {
    private val origin = RoutePoint(46.52, 6.63)
    private val destination = RoutePoint(46.521, 6.629)
    private val quiet = QuietPlaceRoute(origin, destination, "p1", "Library", 125.0)

    @Test
    fun supersedesPreviousRouteAndRefitsEvenForTheSameDestination() = runTest {
        val controller = RouteController(this) { start, end ->
            delay(if (end == destination) 1_000 else 100)
            WalkingRoute(listOf(start, end), 150.0)
        }
        controller.start(quiet)
        assertTrue(controller.loading)
        val second = quiet.copy(destination = RoutePoint(46.52, 6.631), destinationId = "p2")
        controller.start(second)
        advanceUntilIdle()
        assertEquals("p2", controller.active?.destinationId)
        assertEquals(WalkingRoute(listOf(origin, second.destination), 150.0), controller.walkingRoute)
        assertFalse(controller.loading)
        controller.start(second)
        assertEquals(3, controller.requestCount)
        controller.end()
        advanceUntilIdle()
        assertNull(controller.active)
        assertNull(controller.walkingRoute)
        assertFalse(controller.loading)
    }

    @Test
    fun keepsTheDestinationWhenWalkingDirectionsAreUnavailable() = runTest {
        val controller = RouteController(this) { _, _ -> error("Offline") }
        controller.start(quiet)
        advanceUntilIdle()
        assertEquals("Library", controller.active?.destinationName)
        assertNull(controller.walkingRoute)
        assertFalse(controller.loading)
    }
}
