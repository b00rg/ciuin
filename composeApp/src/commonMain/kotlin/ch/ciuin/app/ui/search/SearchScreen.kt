package ch.ciuin.app.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.ciuin.app.core.AppEnvironment
import ch.ciuin.shared.model.Place

/**
 * Full-screen search opened from the map's search bar. [personalScores] is predicted overwhelm by place id
 * (the "For you" rating shown on each match). [onPick] gets the chosen match or recent place.
 */
@Composable
internal fun SearchScreen(
    environment: AppEnvironment,
    places: List<Place>,
    personalScores: Map<String, Double>,
    onPick: (Place) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
