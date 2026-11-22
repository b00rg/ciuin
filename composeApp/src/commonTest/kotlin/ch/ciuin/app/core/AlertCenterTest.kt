package ch.ciuin.app.core

import ch.ciuin.app.personal.LiveWindow
import ch.ciuin.app.route.QuietPlaceRoute
import ch.ciuin.app.route.RoutePoint
import ch.ciuin.app.route.WalkingRoute
import ch.ciuin.shared.model.FeatureVector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AlertCenterTest {
    @Test
    fun routesAcknowledgesTheOriginalWindowAndSnoozes() = runTest {
        val settings = SettingsController(MemorySettingsStorage())
        val routes = RouteController(this) { start, end -> WalkingRoute(listOf(start, end), 144.0) }
        val window = LiveWindow(FeatureVector(dbMean = 70.0), "2026-11-22T13:00:00Z", 0.82)
        val quiet = QuietPlaceRoute(RoutePoint(46.52, 6.63), RoutePoint(46.521, 6.63), "p1", "Library", 112.0)
        val alert = ActiveAlert("id", 70.0, 3_000, window, QuietPlaceSuggestion(quiet, 37.0, 40.0, true))
        val okay = mutableListOf<LiveWindow>()
        val snoozed = mutableListOf<Int>()
        val dismissed = mutableListOf<String>()
        val center = AlertCenter(
            settings, routes, recordOkay = { okay.add(it.window) },
            onSnooze = { snoozed.add(it) }, onDismiss = { dismissed.add(it.id) },
        )

        center.show(alert)
        center.updateDuration("id", 8_000)
        assertEquals(8_000L, center.active?.aboveLimitMillis)
        center.okayHere()
        assertEquals(window, okay.single())
        assertNull(center.active)

        center.show(alert)
        center.takeMeThere()
        advanceUntilIdle()
        assertEquals("Library", routes.active?.destinationName)
        assertEquals(144.0, routes.walkingRoute?.distanceMeters)
        assertNull(center.active)

        center.show(alert)
        center.snooze()
        center.show(alert)
        center.snooze(minutes = 20)
        assertEquals(listOf(15, 20), snoozed)
        center.show(alert)
        center.dismiss()
        assertEquals(List(5) { "id" }, dismissed)

        settings.gentleAlerts = false
        center.show(alert)
        assertNull(center.active)
    }
}
