package ch.ciuin.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/** Settings > Accessibility > Motion > Reduce Motion. */
@Composable
actual fun systemReduceMotion(): Boolean = remember { UIAccessibilityIsReduceMotionEnabled() }
