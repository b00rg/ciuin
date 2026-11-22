package ch.ciuin.app.ui.route

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.ciuin.app.core.AppEnvironment
import ch.ciuin.app.route.QuietPlaceRoute
import ch.ciuin.app.route.WalkingRoute
import ch.ciuin.shared.model.Place

/** The black "Walking to …" banner the map shows at the top while a route is active. */
@Composable
internal fun RouteHeader(
    route: QuietPlaceRoute,
    walkingRoute: WalkingRoute?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}

/**
 * The bottom sheet the map shows while a route is active: "Getting calmer", the turn-by-turn steps,
 * "End route" and "I'm okay now". [destination] is the place being walked to when the map knows it.
 */
@Composable
internal fun RouteSheet(
    environment: AppEnvironment,
    route: QuietPlaceRoute,
    walkingRoute: WalkingRoute?,
    loading: Boolean,
    destination: Place?,
    onEnd: () -> Unit,
    onOkayNow: () -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    Box(modifier)
}
