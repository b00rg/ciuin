package ch.ciuin.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ch.ciuin.app.personal.PersonalModelController
import ch.ciuin.app.route.QuietPlaceRoute
import ch.ciuin.app.route.WalkingRoute
import ch.ciuin.app.route.WalkingRouter
import ch.ciuin.shared.api.PlacesApi

@Composable
internal fun App(
    personalModel: PersonalModelController,
    api: PlacesApi = remember { createPlacesApi() },
    liveListening: Boolean = false,
    onLiveListeningChange: ((Boolean) -> Unit)? = null,
    liveListenerMessage: String? = null,
    quietPlaceRoute: QuietPlaceRoute? = null,
    onClearQuietPlaceRoute: () -> Unit = {},
    quietPlaceRouteRequest: Int = 0,
    walkingRouter: WalkingRouter? = null,
) {
    val personalSnackbar = remember { SnackbarHostState() }
    val personalState = personalModel.state
    LaunchedEffect(personalState.notice) {
        val notice = personalState.notice ?: return@LaunchedEffect
        personalSnackbar.showSnackbar(notice.message)
        personalModel.dismissNotice(notice)
    }
    var walkingRoute by remember(quietPlaceRoute, quietPlaceRouteRequest, walkingRouter) {
        mutableStateOf<WalkingRoute?>(null)
    }
    var walkingRouteLoading by remember(quietPlaceRoute, quietPlaceRouteRequest, walkingRouter) {
        mutableStateOf(quietPlaceRoute != null && walkingRouter != null)
    }
    LaunchedEffect(quietPlaceRoute, quietPlaceRouteRequest, walkingRouter) {
        if (quietPlaceRoute != null && walkingRouter != null) {
            walkingRoute = walkingRouter.findRoute(quietPlaceRoute.origin, quietPlaceRoute.destination)
            walkingRouteLoading = false
        }
    }

    MaterialTheme(typography = appTypography()) {
        Surface(Modifier.fillMaxSize()) {
            MapScreen(
                api = api,
                personal = personalState.session,
                personalSnackbar = personalSnackbar,
                personalBusy = personalState.busy,
                liveListening = liveListening,
                liveScore = personalModel.liveScore,
                onLiveListeningChange = onLiveListeningChange,
                liveListenerMessage = liveListenerMessage,
                quietPlaceRoute = quietPlaceRoute,
                onClearQuietPlaceRoute = onClearQuietPlaceRoute,
                quietPlaceRouteRequest = quietPlaceRouteRequest,
                walkingRoute = walkingRoute,
                walkingRouteLoading = walkingRouteLoading,
                onSelectPreset = personalModel::selectPreset,
                onFeedback = personalModel::recordFeedback,
                onResetPersonal = personalModel::reset,
                onRetryPersonal = personalModel::load,
            )
        }
    }
}
