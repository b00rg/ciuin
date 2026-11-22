package ch.ciuin.app.measure

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.ciuin.app.core.LocationSource
import ch.ciuin.app.core.SettingsController
import ch.ciuin.app.route.RoutePoint
import ch.ciuin.shared.api.FallbackPlacesApi
import ch.ciuin.shared.api.PlacesApi
import ch.ciuin.shared.model.Measurement
import ch.ciuin.shared.model.MeasurementResult
import ch.ciuin.shared.model.Place
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.round
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal const val UNLISTED_MEASUREMENT_PLACE_ID = "unlisted"

internal sealed interface MeasurementPhase {
    data object Ready : MeasurementPhase
    data object Requesting : MeasurementPhase
    data object PermissionDenied : MeasurementPhase
    data class Capturing(
        val remainingSeconds: Int,
        val readings: MeasurementReadings,
        val permissions: CapturePermissions,
        val startedAt: TimeMark,
    ) : MeasurementPhase
    data class Review(val review: MeasurementReview, val error: String? = null) : MeasurementPhase
    data class Uploading(val review: MeasurementReview) : MeasurementPhase
    data class Done(val stored: Boolean, val updatedPlace: Place?) : MeasurementPhase
}

/** A review owns its measurement ID, so a failed share can be retried with the same payload. */
internal class MeasurementController(
    internal val capture: MeasurementCapture,
    private val api: PlacesApi,
    private val settings: SettingsController,
    private val location: LocationSource,
    private val scope: CoroutineScope,
    private val collectMeasurement: suspend (
        String, RoutePoint, MeasurementCapture, CapturePermissions, (MeasurementReadings) -> Unit,
    ) -> MeasurementReview = ::measurePlace,
) {
    var phase by mutableStateOf<MeasurementPhase>(MeasurementPhase.Ready)
        private set
    var place by mutableStateOf<Place?>(null)
        private set
    var startError by mutableStateOf<String?>(null)
        private set
    var pendingUploads by mutableIntStateOf(0)
        private set
    private var inForeground by mutableStateOf(true)
    val foreground: Boolean get() = inForeground
    var cancellationCount by mutableIntStateOf(0)
        private set

    private var operation: Job? = null
    private var pendingWatcher: Job? = null
    private var queueRetry: Job? = null
    private val fallback = api as? FallbackPlacesApi

    // The old measurement dialog still captures its own review until the redesigned UI replaces it.
    internal val legacyApi: PlacesApi = object : PlacesApi by api {
        override suspend fun postMeasurement(m: Measurement): MeasurementResult = share(m)
    }

    init {
        if (fallback != null) scope.launch { refreshPending() }
    }

    fun prepare(place: Place? = null) {
        if (phase is MeasurementPhase.Uploading) return
        cancel()
        this.place = place
    }

    fun start() {
        if (!foreground || (phase != MeasurementPhase.Ready && phase != MeasurementPhase.PermissionDenied)) return
        val destination = place?.let { RoutePoint(it.lat, it.lon) } ?: location.current
        if (destination?.isValid != true) {
            startError = "Your location is unavailable."
            return
        }
        startError = null
        val placeId = place?.placeId ?: UNLISTED_MEASUREMENT_PLACE_ID
        // An unlisted measurement needs a map location without retaining a raw GPS fix.
        val point = if (place == null) RoutePoint(coarseCoordinate(destination.lat), coarseCoordinate(destination.lon)) else destination
        phase = MeasurementPhase.Requesting
        val previous = operation
        operation = scope.launch {
            previous?.join()
            val permissions = try {
                capture.requestPermissions()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CapturePermissions(camera = false, microphone = false)
            }
            currentCoroutineContext().ensureActive()
            if (!permissions.camera && !permissions.microphone && !capture.canMeasureLux &&
                !(permissions.bluetooth && capture.canScanBluetooth)
            ) {
                phase = MeasurementPhase.PermissionDenied
                return@launch
            }
            takeReading(placeId, point, permissions)
        }
    }

    private suspend fun takeReading(placeId: String, point: RoutePoint, permissions: CapturePermissions) = coroutineScope {
        phase = MeasurementPhase.Capturing(
            MEASUREMENT_SECONDS, MeasurementReadings(), permissions, TimeSource.Monotonic.markNow(),
        )
        val countdown = launch {
            for (remaining in MEASUREMENT_SECONDS - 1 downTo 0) {
                delay(1_000)
                val current = phase as? MeasurementPhase.Capturing ?: break
                phase = current.copy(remainingSeconds = remaining)
            }
        }
        try {
            val review = collectMeasurement(placeId, point, capture, permissions) { readings ->
                val current = phase as? MeasurementPhase.Capturing
                if (current != null) phase = current.copy(readings = readings)
            }
            currentCoroutineContext().ensureActive()
            phase = MeasurementPhase.Review(review)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            phase = MeasurementPhase.Ready
            startError = "Couldn't finish measuring. Try again."
        } finally {
            countdown.cancel()
        }
    }

    fun upload() {
        val review = (phase as? MeasurementPhase.Review)?.review ?: return
        if (!review.canUpload) return
        phase = MeasurementPhase.Uploading(review)
        operation = scope.launch {
            phase = try {
                val result = share(review.measurement)
                MeasurementPhase.Done(result.stored, result.place)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                MeasurementPhase.Review(review, "Upload failed. Try again.")
            }
        }
    }

    fun cancel() {
        if (phase is MeasurementPhase.Uploading) return
        operation?.cancel()
        phase = MeasurementPhase.Ready
        startError = null
    }

    fun discard() = cancel()

    fun setForeground(foreground: Boolean) {
        if (this.foreground == foreground) return
        inForeground = foreground
        if (!foreground) {
            cancellationCount++
            if (phase == MeasurementPhase.Requesting || phase is MeasurementPhase.Capturing) cancel()
        }
    }

    fun retryNow() {
        if (fallback == null || queueRetry?.isActive == true) return
        queueRetry = scope.launch {
            // A successful read triggers FallbackPlacesApi's existing queue flush.
            fallback.stats()
            refreshPending()
        }
    }

    private suspend fun share(measurement: Measurement): MeasurementResult {
        val result = api.postMeasurement(measurement)
        if (result.stored || fallback != null) settings.recordSharedMeasurement()
        refreshPending()
        return result
    }

    private suspend fun refreshPending() {
        val queue = fallback ?: return
        pendingUploads = queue.pendingCount()
        if (pendingUploads > 0 && pendingWatcher?.isActive != true) {
            pendingWatcher = scope.launch {
                while (pendingUploads > 0) {
                    delay(1_000)
                    pendingUploads = queue.pendingCount()
                }
            }
        }
    }
}

private fun coarseCoordinate(value: Double) = round(value * 1_000) / 1_000
