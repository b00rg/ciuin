package ch.ciuin.app.map

import androidx.compose.ui.geometry.Size
import ch.ciuin.app.route.QuietPlaceRoute
import ch.ciuin.app.route.RoutePoint
import ch.ciuin.app.route.WalkingRoute
import kotlin.test.Test
import kotlin.test.assertEquals

class MapRouteCameraTest {
    @Test
    fun fitsTheShortRouteAcrossTheAntimeridian() {
        val route = QuietPlaceRoute(
            origin = RoutePoint(0.0, 179.999),
            destination = RoutePoint(0.0, -179.999),
            destinationId = "place",
            destinationName = "Quiet place",
            distanceMeters = 222.4,
        )
        val state = MapState(lat = 46.52, lon = 6.63, zoom = 14.0)
        val viewport = Size(320f, 640f)

        state.fitRoute(projectQuietPlaceRoute(route, walkingRoute = null), available = Size(240f, 288f), tilePx = 256f)

        val start = state.toScreen(route.origin.lat, route.origin.lon, viewport, tilePx = 256f)
        val end = state.toScreen(route.destination.lat, route.destination.lon, viewport, tilePx = 256f)
        assertEquals(40f, start.x, absoluteTolerance = 0.01f)
        assertEquals(280f, end.x, absoluteTolerance = 0.01f)
        assertEquals(320f, start.y, absoluteTolerance = 0.01f)
        assertEquals(320f, end.y, absoluteTolerance = 0.01f)
    }

    @Test
    fun fitsTheWalkingDetourAcrossTheAntimeridian() {
        val route = QuietPlaceRoute(
            origin = RoutePoint(0.0, 179.999),
            destination = RoutePoint(0.0, -179.999),
            destinationId = "place",
            destinationName = "Quiet place",
            distanceMeters = 222.4,
        )
        val bend = RoutePoint(0.004, 180.0)
        val walkingRoute = WalkingRoute(listOf(route.origin, bend, route.destination), distanceMeters = 917.0)
        val state = MapState(lat = 46.52, lon = 6.63, zoom = 14.0)
        val viewport = Size(320f, 640f)

        state.fitRoute(projectQuietPlaceRoute(route, walkingRoute), available = Size(240f, 288f), tilePx = 256f)

        val start = state.toScreen(route.origin.lat, route.origin.lon, viewport, tilePx = 256f)
        val turn = state.toScreen(bend.lat, bend.lon, viewport, tilePx = 256f)
        val end = state.toScreen(route.destination.lat, route.destination.lon, viewport, tilePx = 256f)
        assertEquals(88f, start.x, absoluteTolerance = 0.01f)
        assertEquals(160f, turn.x, absoluteTolerance = 0.01f)
        assertEquals(232f, end.x, absoluteTolerance = 0.01f)
        assertEquals(464f, start.y, absoluteTolerance = 0.01f)
        assertEquals(176f, turn.y, absoluteTolerance = 0.01f)
        assertEquals(464f, end.y, absoluteTolerance = 0.01f)
    }
}
