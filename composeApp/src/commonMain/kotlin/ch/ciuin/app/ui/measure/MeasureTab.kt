package ch.ciuin.app.ui.measure

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import ch.ciuin.app.core.AppEnvironment
import ch.ciuin.app.core.AppPermission
import ch.ciuin.app.core.PermissionStatus
import ch.ciuin.app.insight.nearestPlaces
import ch.ciuin.app.measure.MeasurementPhase
import ch.ciuin.app.measure.MeasurementReview
import ch.ciuin.app.measure.UNLISTED_MEASUREMENT_PLACE_ID
import ch.ciuin.app.route.RoutePoint
import ch.ciuin.app.route.distanceMeters
import ch.ciuin.shared.model.Place
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun MeasureTab(
    environment: AppEnvironment,
    places: List<Place>,
    preselected: Place?,
    onBackToMap: () -> Unit,
    choiceId: String?,
    onChoiceChanged: (String) -> Unit,
    onResetPlace: () -> Unit,
    onFindPlace: (() -> Unit)?,
    tabBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onPlaceUpdated: (Place) -> Unit = {},
) {
    val controller = environment.measurement
    if (controller == null) {
        MeasureStartScreen(
            choices = emptyList(),
            selected = null,
            onSelect = {},
            onStart = {},
            tabBar = tabBar,
            modifier = modifier,
            canMeasure = false,
        )
        return
    }

    val scope = rememberCoroutineScope()
    var feedback by remember { mutableStateOf<MeasureFeedback?>(null) }
    var submittedReview by remember { mutableStateOf<MeasurementReview?>(null) }
    val phase = controller.phase

    DisposableEffect(controller) {
        onDispose { controller.cancel() }
    }
    LaunchedEffect(phase) {
        if (phase is MeasurementPhase.Done) {
            phase.updatedPlace?.let(onPlaceUpdated)
            val features = submittedReview?.measurement?.features
            if (features != null) {
                feedback?.let { environment.personalModel.recordMeasurementFeedback(features, it.label) }
            }
            feedback = null
        }
    }

    fun backToChoices() {
        feedback = null
        submittedReview = null
        controller.cancel()
    }

    BackHandler(enabled = phase != MeasurementPhase.Ready && phase != MeasurementPhase.PermissionDenied) {
        when (phase) {
            is MeasurementPhase.Uploading -> Unit
            is MeasurementPhase.Done -> onBackToMap()
            else -> backToChoices()
        }
    }

    when (phase) {
        MeasurementPhase.Ready, MeasurementPhase.PermissionDenied -> {
            val origin = environment.location.current?.takeIf { it.isValid }
            val permission = environment.permissions.status(AppPermission.LOCATION)
            val choices = remember(places, origin, preselected, choiceId) {
                val nearby = placeChoices(places, origin, preselected)
                if (choiceId == null || nearby.any { it.key == choiceId }) nearby
                else places.firstOrNull { it.placeId == choiceId }
                    ?.let { placeChoices(places, origin, it) } ?: nearby
            }
            val selected = if (choiceId == null) choices.firstOrNull() else choices.firstOrNull { it.key == choiceId }
            MeasureStartScreen(
                choices = choices,
                selected = selected,
                onSelect = { onChoiceChanged(it.key) },
                onStart = {
                    val choice = selected ?: return@MeasureStartScreen
                    controller.prepare(choice.place)
                    controller.start()
                },
                tabBar = tabBar,
                modifier = modifier,
                locationNotice = if (origin == null) permission.locationNotice() else null,
                onAllowLocation = { scope.launch { environment.permissions.request(AppPermission.LOCATION) } },
                onFindPlace = onFindPlace,
                nothingNearby = origin != null && choices.none { it.place != null },
                error = controller.startError ?: when {
                    phase != MeasurementPhase.PermissionDenied -> null
                    controller.capture.canScanBluetooth -> "Allow microphone, camera or nearby-device access in Settings to measure."
                    else -> "Allow microphone or camera access in Settings to measure."
                },
                canStart = controller.foreground,
            )
        }
        MeasurementPhase.Requesting, is MeasurementPhase.Capturing -> MeasureCaptureScreen(
            placeName = controller.place?.name ?: "This spot",
            capture = phase as? MeasurementPhase.Capturing,
            levels = remember(controller.capture) { controller.capture.audioLevels() },
            onCancel = ::backToChoices,
            modifier = modifier,
        )
        is MeasurementPhase.Review -> MeasureReviewScreen(
            review = phase.review,
            place = controller.place,
            feedback = feedback,
            onFeedback = { feedback = it },
            onShare = {
                submittedReview = phase.review
                controller.upload()
            },
            onDiscard = ::backToChoices,
            uploading = false,
            error = phase.error,
            modifier = modifier,
        )
        is MeasurementPhase.Uploading -> MeasureReviewScreen(
            review = phase.review,
            place = controller.place,
            feedback = feedback,
            onFeedback = null,
            onShare = {},
            onDiscard = {},
            uploading = true,
            error = null,
            modifier = modifier,
        )
        is MeasurementPhase.Done -> MeasureSharedScreen(
            stored = phase.stored,
            updatedPlace = phase.updatedPlace,
            summary = submittedReview?.let(::sharedSummary),
            onBackToMap = onBackToMap,
            onMeasureAgain = {
                onResetPlace()
                backToChoices()
            },
            modifier = modifier,
        )
    }
}

private val PlaceChoice.key: String get() = place?.placeId ?: UNLISTED_MEASUREMENT_PLACE_ID

private fun placeChoices(places: List<Place>, origin: RoutePoint?, preselected: Place?): List<PlaceChoice> =
    buildList {
        if (preselected != null) {
            val point = RoutePoint(preselected.lat, preselected.lon)
            val distance = if (origin != null && point.isValid) distanceMeters(origin, point) else null
            add(PlaceChoice(preselected, placeCardSubtitle(preselected, distance)))
        }
        if (origin != null) {
            val available = places.filterNot { it.placeId == preselected?.placeId }
            nearestPlaces(available, origin, limit = 2 - size).forEach { (place, distance) ->
                add(PlaceChoice(place, placeCardSubtitle(place, distance)))
            }
            add(PlaceChoice(null, "Measure this spot without a place"))
        }
    }

private fun PermissionStatus.locationNotice(): LocationNotice = when (this) {
    PermissionStatus.NOT_DETERMINED -> LocationNotice.Ask
    PermissionStatus.DENIED -> LocationNotice.Off
    PermissionStatus.UNAVAILABLE -> LocationNotice.Unavailable
    PermissionStatus.GRANTED -> LocationNotice.Finding
}
