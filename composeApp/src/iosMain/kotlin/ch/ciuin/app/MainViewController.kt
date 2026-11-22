package ch.ciuin.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import ch.ciuin.app.core.AlertCenter
import ch.ciuin.app.core.AppEnvironment
import ch.ciuin.app.core.IosLocationSource
import ch.ciuin.app.core.IosPermissions
import ch.ciuin.app.core.IosSettingsStorage
import ch.ciuin.app.core.ModelCatalog
import ch.ciuin.app.core.RouteController
import ch.ciuin.app.core.SettingsController
import ch.ciuin.app.measure.IosMeasurementCapture
import ch.ciuin.app.measure.IosMeasurementDevices
import ch.ciuin.app.measure.MeasurementController
import ch.ciuin.app.personal.IosPersonalModelStorage
import ch.ciuin.app.personal.PersonalModelController
import ch.ciuin.app.route.WalkingRouter
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState.UIApplicationStateBackground
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

fun MainViewController(measurementDevices: IosMeasurementDevices) = ComposeUIViewController {
    val scope = rememberCoroutineScope()
    val location = remember { IosLocationSource() }
    val environment = remember(measurementDevices) {
        val api = createPlacesApi(BundledPlacesApi(::readBundledPlaces))
        val personal = PersonalModelController(IosPersonalModelStorage(), scope)
        val settings = SettingsController(IosSettingsStorage(), UIAccessibilityIsReduceMotionEnabled(), scope)
        val router = WalkingRouter()
        val routes = RouteController(scope, router)
        AppEnvironment(
            api, personal, router, settings, IosPermissions(scope, location), location,
            listening = null,
            alerts = AlertCenter(settings, routes, recordOkay = { alert ->
                personal.recordAlertFeedback(alert.window, label = 0)
            }),
            routes = routes,
            measurement = MeasurementController(IosMeasurementCapture(measurementDevices), api, settings, location, scope),
            models = ModelCatalog(settings, emptySet()).apply { load(scope) },
        )
    }
    DisposableEffect(environment) {
        val notifications = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue
        fun foreground(value: Boolean) {
            environment.measurement?.setForeground(value)
            location.setForeground(value)
            if (value) {
                environment.permissions.refresh()
                environment.settings.refreshSystemReduceMotion(UIAccessibilityIsReduceMotionEnabled())
            }
        }
        foreground(UIApplication.sharedApplication.applicationState != UIApplicationStateBackground)
        val background = notifications.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, queue) {
            foreground(false)
            measurementDevices.stopAll()
        }
        val foregrounded = notifications.addObserverForName(UIApplicationDidBecomeActiveNotification, null, queue) {
            foreground(true)
        }
        onDispose {
            notifications.removeObserver(background)
            notifications.removeObserver(foregrounded)
            location.stop()
            measurementDevices.stopAll()
            environment.walkingRouter?.close()
        }
    }
    val base = LocalViewConfiguration.current
    val density = LocalDensity.current
    val config = remember(base, density) { AndroidGestureThresholds(base, density) }
    CompositionLocalProvider(LocalViewConfiguration provides config) {
        App(environment)
    }
}

/**
 * Android's gesture thresholds, so the map pans and taps exactly as it does there.
 * Compose's iOS defaults differ: 10 pt drag slop, 500 ms long press, uncapped fling.
 */
private class AndroidGestureThresholds(base: ViewConfiguration, density: Density) : ViewConfiguration by base {
    override val touchSlop: Float = with(density) { 8.dp.toPx() }
    override val longPressTimeoutMillis: Long = 400
    override val maximumFlingVelocity: Float = with(density) { 8000.dp.toPx() }
}
