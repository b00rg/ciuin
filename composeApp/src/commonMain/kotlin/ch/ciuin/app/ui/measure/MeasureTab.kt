package ch.ciuin.app.ui.measure

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.ciuin.app.core.AppEnvironment
import ch.ciuin.shared.model.Place

/**
 * The Measure tab: pick a place, capture for 30 s, review and share, then the "duck joined" screen.
 * [places] is every place the map has loaded; [preselected] is set when the user arrived from a place's
 * "Measure here". Draws [tabBar] at the bottom of the screens that show it (only the start screen does).
 */
@Composable
internal fun MeasureTab(
    environment: AppEnvironment,
    places: List<Place>,
    preselected: Place?,
    onBackToMap: () -> Unit,
    tabBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
