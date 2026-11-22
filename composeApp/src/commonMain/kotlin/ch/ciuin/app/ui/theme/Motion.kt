package ch.ciuin.app.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True when motion should be reduced: the system setting, or the app's own "Reduce motion" switch on
 * You. Character loops then stop on their rest pose and every transition becomes a 150 ms fade.
 * [CiuinTheme] provides it.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * Timings from the "Interface" table on the Motion board. "No loop is faster than a breath": nothing
 * here repeats, and the character loops live in `ui/characters`.
 */
object CiuinMotion {
    /** CSS `ease`. */
    val Ease: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** CSS `ease-out`. */
    val EaseOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    /** CSS `ease-in-out`. */
    val EaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** A sheet rises from the bottom edge while the map stays put: 320 ms spring. */
    const val SHEET_MILLIS = 320

    /** Hexagons cross-fade in place when the layer changes: 200 ms fade. */
    const val LAYER_SWITCH_MILLIS = 200

    /** The sheep cross-fades between poses and its label changes with it: 400 ms ease. */
    const val SHEEP_STATE_MILLIS = 400

    /** The measure ring fills clockwise at a constant rate: 30 s linear. */
    const val MEASURE_MILLIS = 30_000

    /** A button scales to [PRESS_SCALE] and back: 90 ms. */
    const val PRESS_MILLIS = 90
    const val PRESS_SCALE = 0.97f

    /** An alert fades in and rises [ALERT_RISE_DP] without flashing: 280 ms ease-out. */
    const val ALERT_MILLIS = 280
    const val ALERT_RISE_DP = 12

    /** An alert shows at most once in this long. */
    const val ALERT_MIN_INTERVAL_MILLIS = 120_000

    /** With Reduce motion on, every transition is a fade this long. */
    const val REDUCED_FADE_MILLIS = 150

    /** Spring for a sheet opening, settling in about [SHEET_MILLIS] without overshoot. */
    fun <T> sheetSpring(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 380f)

    /** Fade for a layer switch. */
    fun <T> layerSwitch(): FiniteAnimationSpec<T> = tween(LAYER_SWITCH_MILLIS, easing = LinearEasing)

    /** Fade and rise for an alert appearing. */
    fun <T> alertAppear(): FiniteAnimationSpec<T> = tween(ALERT_MILLIS, easing = EaseOut)

    /** The fade every transition falls back to under Reduce motion. */
    fun <T> reducedFade(): FiniteAnimationSpec<T> = tween(REDUCED_FADE_MILLIS, easing = LinearEasing)

    /** [spec], or the 150 ms fade when Reduce motion is on. Use it for any transition a screen adds. */
    @Composable
    @ReadOnlyComposable
    fun <T> orReduced(spec: AnimationSpec<T>): AnimationSpec<T> =
        if (LocalReduceMotion.current) reducedFade() else spec
}
