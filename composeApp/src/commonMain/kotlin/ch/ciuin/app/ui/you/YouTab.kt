package ch.ciuin.app.ui.you

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.ciuin.app.core.AppEnvironment

/**
 * The You tab and everything behind it: settings, starting point, the reset sheet, "On this phone"
 * and "What is shared". Draws [tabBar] at the bottom of the screens that show it (only the root does).
 */
@Composable
internal fun YouTab(environment: AppEnvironment, tabBar: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier)
}
