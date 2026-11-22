package ch.ciuin.app.measure

import ch.ciuin.app.CROWD_BUSY_DEVICES
import ch.ciuin.app.CROWD_QUIET_DEVICES
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import ch.ciuin.shared.api.PlacesApi
import ch.ciuin.shared.model.Place
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private sealed interface Phase {
    data object Ready : Phase
    data object Requesting : Phase
    data object PermissionDenied : Phase
    data class Capturing(val permissions: CapturePermissions) : Phase
    data class Review(val value: MeasurementReview, val message: String? = null) : Phase
    data class Uploading(val review: MeasurementReview) : Phase
}

@Composable
internal fun MeasurementDialog(
    place: Place,
    capture: MeasurementCapture,
    foreground: Boolean,
    cancellationCount: Int,
    api: PlacesApi,
    onDismiss: () -> Unit,
    onComplete: (stored: Boolean) -> Unit,
) {
    var phase by remember(place.placeId) { mutableStateOf<Phase>(Phase.Ready) }
    var captureCancellationCount by remember(place.placeId) { mutableIntStateOf(cancellationCount) }
    val current = phase
    val capturing = current is Phase.Requesting || current is Phase.Capturing
    if (capturing && (!foreground || captureCancellationCount != cancellationCount)) {
        // Leaving this branch cancels any permission wait or open sensor streams.
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    var remaining by remember(place.placeId) { mutableIntStateOf(MEASUREMENT_SECONDS) }
    LaunchedEffect(place.placeId, current) {
        when (current) {
            Phase.Requesting -> {
                phase = try {
                    val permissions = capture.requestPermissions()
                    if (!permissions.camera && !permissions.microphone && !capture.canMeasureLux) {
                        Phase.PermissionDenied
                    } else {
                        Phase.Capturing(permissions)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    if (capture.canMeasureLux) Phase.Capturing(CapturePermissions(camera = false, microphone = false))
                    else Phase.PermissionDenied
                }
            }
            is Phase.Capturing -> phase = Phase.Review(measurePlace(place, capture, current.permissions))
            is Phase.Uploading -> {
                try {
                    onComplete(api.postMeasurement(current.review.measurement).stored)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    phase = Phase.Review(current.review, "Upload failed. Try again.")
                }
            }
            else -> Unit
        }
    }
    if (current is Phase.Capturing) {
        LaunchedEffect(Unit) {
            for (seconds in MEASUREMENT_SECONDS - 1 downTo 0) {
                delay(1_000)
                remaining = seconds
            }
        }
    }

    // A retry starts from the same review and reuses its measurement ID.
    val uploading = current is Phase.Uploading
    AlertDialog(
        onDismissRequest = { if (!uploading) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !uploading, dismissOnClickOutside = false),
        title = {
            Text(when (current) {
                Phase.Ready, Phase.Requesting, Phase.PermissionDenied -> "Measure here"
                is Phase.Capturing -> "Measuring"
                is Phase.Review -> "Review measurement"
                is Phase.Uploading -> "Uploading measurement"
            })
        },
        text = {
            Column(
                Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(place.name, style = MaterialTheme.typography.titleSmall)
                when (current) {
                    Phase.Ready -> {
                        Text("Keep the app open for 30 seconds. Hold the phone steady and point the rear camera toward the lights.")
                        Text("The camera only measures light and flicker, never people. Video, audio and Bluetooth stay on the phone: only the readings are shared, after you review them.")
                    }
                    Phase.Requesting -> Text("Waiting for camera, microphone and nearby-devices permissions…")
                    Phase.PermissionDenied -> Text(
                        "Camera and microphone access are unavailable. Allow access in system settings to take a measurement.",
                    )
                    is Phase.Capturing -> {
                        LinearProgressIndicator(
                            progress = { (MEASUREMENT_SECONDS - remaining) / MEASUREMENT_SECONDS.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("$remaining seconds remaining")
                        if (!current.permissions.camera) Text("Camera access is unavailable.")
                        if (!current.permissions.microphone) Text("Microphone access is unavailable.")
                    }
                    is Phase.Review -> {
                        ReviewReadings(current.value)
                        current.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    is Phase.Uploading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            when (current) {
                Phase.Ready, Phase.PermissionDenied -> {
                    Button(
                        onClick = {
                            captureCancellationCount = cancellationCount
                            phase = Phase.Requesting
                        },
                        enabled = foreground,
                    ) {
                        Text(if (current == Phase.Ready) "Start" else "Try again")
                    }
                }
                is Phase.Review -> if (current.value.canUpload) {
                    Button(onClick = { phase = Phase.Uploading(current.value) }) { Text("Confirm and upload") }
                }
                else -> Unit
            }
        },
        dismissButton = {
            if (!uploading) TextButton(onClick = onDismiss) {
                Text(when (current) {
                    is Phase.Review -> if (current.value.canUpload) "Discard" else "Close"
                    else -> "Cancel"
                })
            }
        },
    )
}

@Composable
private fun ReviewReadings(review: MeasurementReview) {
    val features = review.measurement.features
    val mean = features.dbMean
    val peak = features.dbPeak
    val dbfs = review.dbfs
    val flicker = review.flicker
    val classes = features.soundClasses
    val topClass = classes?.maxByOrNull { it.value }?.takeIf { it.value > 0 }

    Text(when {
        mean != null && peak != null -> "Sound  ${mean.roundToInt()} dB(A) average · ${peak.roundToInt()} dB(A) peak (estimated)"
        dbfs != null -> "Sound  ${dbfs.mean.roundToInt()} dBFS(A) average · ${dbfs.peak.roundToInt()} dBFS(A) peak (shown on this device only)"
        !review.permissions.microphone -> "Sound  Microphone access unavailable"
        else -> "Sound  Insufficient samples"
    })
    if (review.permissions.microphone) {
        Text(when {
            topClass != null -> "Sound class  ${topClass.key.replace('_', ' ')} (${(topClass.value * 100).roundToInt()}%)"
            classes != null -> "Sound class  Quiet"
            else -> "Sound classification  Unavailable"
        })
    }
    Text(when (val lux = features.luxMean) {
        null -> if (review.canMeasureLux) "Brightness  No lux reading" else "Brightness  Lux unavailable on this device"
        else -> "Brightness  ${decimal(lux, 1)} lux"
    })
    if (flicker == null) {
        Text(if (review.permissions.camera) "Flicker  Insufficient luminance samples" else "Flicker  Camera access unavailable")
    } else {
        val frequency = flicker.dominantHz?.let { "${decimal(it, 1)} Hz" } ?: "No periodic peak detected"
        Text("Flicker  $frequency · index ${decimal(flicker.flickerIndex, 3)}")
        Text(
            "Detectable range ${decimal(flicker.minHz, 1)}–${decimal(flicker.maxHz, 1)} Hz at ${decimal(flicker.sampleRateHz, 1)} fps. " +
                "Faster flicker can appear at a lower frequency.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(when (val devices = features.nearbyDevices) {
        null -> if (review.permissions.bluetooth) "Crowd  Bluetooth is off" else "Crowd  Nearby-devices access unavailable"
        else -> "Crowd  ${busyness(devices)} · $devices nearby ${if (devices == 1) "device" else "devices"}"
    })
    if (!review.canUpload) Text("No readings available to upload.", style = MaterialTheme.typography.bodySmall)
}

/** Same bands as the map's Crowd layer (Category.CROWD). */
private fun busyness(devices: Int): String = when {
    devices < CROWD_QUIET_DEVICES -> "Quiet"
    devices < CROWD_BUSY_DEVICES -> "Busy"
    else -> "Crowded"
}

private fun decimal(value: Double, places: Int): String {
    val scale = if (places == 1) 10 else 1_000
    val rounded = (value * scale).roundToInt()
    return "${rounded / scale}.${(rounded % scale).toString().padStart(places, '0')}"
}
