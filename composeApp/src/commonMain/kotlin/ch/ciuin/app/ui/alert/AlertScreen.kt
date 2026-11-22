package ch.ciuin.app.ui.alert

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.ciuin.app.core.ActiveAlert
import ch.ciuin.app.core.AppEnvironment

/**
 * Full-screen "It's getting loud here", shown while [AppEnvironment.alerts] has an active alert.
 * [mapPreview] fills the map area of the quiet-place card (the map screen supplies it).
 */
@Composable
internal fun AlertScreen(
    environment: AppEnvironment,
    alert: ActiveAlert,
    mapPreview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
