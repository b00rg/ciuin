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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ch.ciuin.app.core.AppEnvironment
import ch.ciuin.app.measure.MeasurementCapture
import ch.ciuin.app.measure.MeasurementDialog
import ch.ciuin.app.personal.PersonalModelController
import ch.ciuin.app.route.QuietPlaceRoute
import ch.ciuin.app.route.WalkingRoute
import ch.ciuin.app.route.WalkingRouter
import ch.ciuin.shared.api.PlacesApi
import ch.ciuin.shared.model.Place
import kotlinx.coroutines.launch

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
    measurementCapture: MeasurementCapture? = null,
    measurementForeground: Boolean = true,
    measurementCancellationCount: Int = 0,
) {
    val personalSnackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var measuring by remember { mutableStateOf<Place?>(null) }
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
                onMeasure = measurementCapture?.let { { place -> measuring = place } },
            )
        }
        val place = measuring
        if (place != null && measurementCapture != null) {
            MeasurementDialog(
                place,
                measurementCapture,
                measurementForeground,
                measurementCancellationCount,
                api,
                onDismiss = { measuring = null },
                onComplete = { stored ->
                    measuring = null
                    scope.launch {
                        personalSnackbar.showSnackbar(
                            if (stored) "Measurement uploaded" else "Measurement queued while offline",
                        )
                    }
                },
            )
        }
    }
}

@Composable
internal fun App(environment: AppEnvironment) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var measuring by remember { mutableStateOf<Place?>(null) }
    val personal = environment.personalModel
    val state = personal.state
    val listening = environment.listening
    val routes = environment.routes
    val measurement = environment.measurement
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice.message)
        personal.dismissNotice(notice)
    }

    MaterialTheme(typography = appTypography()) {
        Surface(Modifier.fillMaxSize()) {
            MapScreen(
                api = environment.api,
                personal = state.session,
                personalSnackbar = snackbar,
                personalBusy = state.busy,
                liveListening = listening?.isListening ?: false,
                liveScore = listening?.liveScore,
                onLiveListeningChange = listening?.let { it::setListening },
                liveListenerMessage = listening?.message,
                quietPlaceRoute = routes.active,
                onClearQuietPlaceRoute = routes::end,
                quietPlaceRouteRequest = routes.requestCount,
                walkingRoute = routes.walkingRoute,
                walkingRouteLoading = routes.loading,
                onSelectPreset = personal::selectPreset,
                onFeedback = personal::recordFeedback,
                onResetPersonal = personal::reset,
                onRetryPersonal = personal::load,
                onMeasure = measurement?.let { { place -> measuring = place } },
            )
        }
        val place = measuring
        if (place != null && measurement != null) {
            MeasurementDialog(
                place, measurement.capture, measurement.foreground, measurement.cancellationCount, measurement.legacyApi,
                onDismiss = { measuring = null },
                onComplete = { stored ->
                    measuring = null
                    scope.launch {
                        snackbar.showSnackbar(if (stored) "Measurement uploaded" else "Measurement queued while offline")
                    }
                },
            )
        }
    }
}
