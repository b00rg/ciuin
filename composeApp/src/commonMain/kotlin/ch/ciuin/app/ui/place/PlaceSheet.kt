package ch.ciuin.app.ui.place

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.ciuin.app.core.AppEnvironment
import ch.ciuin.shared.model.Place

/**
 * The place detail sheet the map shows over itself for the selected place: the sheet surface and all of
 * its content. Null [onTakeMeThere] / [onMeasure] hide that button (no location, no sensors).
 */
@Composable
internal fun PlaceSheet(
    place: Place,
    environment: AppEnvironment,
    onClose: () -> Unit,
    onTakeMeThere: (() -> Unit)?,
    onMeasure: (() -> Unit)?,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    Box(modifier)
}
