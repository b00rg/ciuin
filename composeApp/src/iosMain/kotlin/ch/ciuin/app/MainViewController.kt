package ch.ciuin.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import ch.ciuin.app.measure.IosMeasurementCapture
import ch.ciuin.app.measure.IosMeasurementDevices
import ch.ciuin.app.personal.IosPersonalModelStorage
import ch.ciuin.app.personal.PersonalModelController
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState.UIApplicationStateBackground
import platform.UIKit.UIApplicationWillEnterForegroundNotification

fun MainViewController(measurementDevices: IosMeasurementDevices) = ComposeUIViewController {
    val scope = rememberCoroutineScope()
    val personalModel = remember { PersonalModelController(IosPersonalModelStorage(), scope) }
    val measurement = remember(measurementDevices) { IosMeasurementCapture(measurementDevices) }
    var foreground by remember {
        mutableStateOf(UIApplication.sharedApplication.applicationState != UIApplicationStateBackground)
    }
    var cancellationCount by remember { mutableIntStateOf(0) }
    DisposableEffect(measurementDevices) {
        val notifications = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue
        val background = notifications.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, queue) {
            foreground = false
            cancellationCount++
            measurementDevices.stopAll()
        }
        val foregrounded = notifications.addObserverForName(UIApplicationWillEnterForegroundNotification, null, queue) {
            foreground = true
        }
        onDispose {
            notifications.removeObserver(background)
            notifications.removeObserver(foregrounded)
        }
    }
    val base = LocalViewConfiguration.current
    val density = LocalDensity.current
    val config = remember(base, density) { AndroidGestureThresholds(base, density) }
    CompositionLocalProvider(LocalViewConfiguration provides config) {
        App(
            personalModel = personalModel,
            measurementCapture = measurement,
            measurementForeground = foreground,
            measurementCancellationCount = cancellationCount,
        )
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
