package ch.ciuin.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController() = ComposeUIViewController {
    val base = LocalViewConfiguration.current
    val density = LocalDensity.current
    val config = remember(base, density) { AndroidGestureThresholds(base, density) }
    CompositionLocalProvider(LocalViewConfiguration provides config) { App() }
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
