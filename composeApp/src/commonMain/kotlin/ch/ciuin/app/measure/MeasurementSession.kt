package ch.ciuin.app.measure

import ch.ciuin.app.route.RoutePoint
import ch.ciuin.shared.camera.CameraFeatures
import ch.ciuin.shared.camera.FlickerResult
import ch.ciuin.shared.camera.LuminanceSample
import ch.ciuin.shared.model.FeatureVector
import ch.ciuin.shared.model.Measurement
import ch.ciuin.shared.model.Place
import ch.ciuin.shared.model.SoundClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal const val MEASUREMENT_SECONDS = 30

/**
 * Bluetooth signal-strength cut-off for "nearby": roughly the same room (side-eye's near/far idea). Weaker
 * signals (other floors, the street) are ignored. First guess; tune with field measurements.
 */
internal const val NEARBY_RSSI_DBM = -80

internal data class MeasurementReview(
    val measurement: Measurement,
    val permissions: CapturePermissions,
    val canMeasureLux: Boolean,
    val flicker: FlickerResult?,
    val dbfs: AudioLevels?,
) {
    val canUpload: Boolean
        get() = measurement.features.let {
            it.dbMean != null || it.soundClasses != null ||
                it.luxMean != null || it.flickerIndex != null || it.nearbyDevices != null
        }
}

internal data class AudioLevels(val mean: Double, val peak: Double)

internal data class MeasurementReadings(
    val db: Double? = null,
    val dbfs: Boolean = false,
    val lux: Double? = null,
    val nearbyDevices: Int? = null,
)

internal suspend fun measurePlace(
    place: Place,
    capture: MeasurementCapture,
    permissions: CapturePermissions,
    onProgress: (MeasurementReadings) -> Unit = {},
): MeasurementReview = measurePlace(place.placeId, RoutePoint(place.lat, place.lon), capture, permissions, onProgress)

@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
internal suspend fun measurePlace(
    placeId: String,
    point: RoutePoint,
    capture: MeasurementCapture,
    permissions: CapturePermissions,
    onProgress: (MeasurementReadings) -> Unit,
): MeasurementReview {
    val startedAt = Clock.System.now().toString()
    val luminance = mutableListOf<LuminanceSample>()
    val nearby = mutableSetOf<Int>()
    var readings = MeasurementReadings()
    var scanned = false
    val audio = mutableListOf<AudioSample>()
    var luxSum = 0.0
    var luxCount = 0
    withTimeoutOrNull(MEASUREMENT_SECONDS * 1_000L) {
        // A sensor can fail on its own. The other collectors still get the entire interval.
        if (permissions.camera) launch {
            captureAvailable {
                capture.camera().collect { sample ->
                    when (sample) {
                        is CameraSample.Luminance -> luminance += LuminanceSample(sample.timestampNanos, sample.value)
                    }
                }
            }
        }
        if (capture.canMeasureLux) launch {
            captureAvailable {
                capture.lux().collect {
                    if (it.isFinite() && it >= 0) {
                        luxSum += it
                        luxCount++
                        readings = readings.copy(lux = it)
                        onProgress(readings)
                    }
                }
            }
        }
        if (permissions.microphone) launch {
            captureAvailable {
                capture.audio().collect { sample ->
                    audio += sample
                    if (sample.dbMean.isFinite()) {
                        readings = readings.copy(db = sample.dbMean, dbfs = sample.dbfs)
                        onProgress(readings)
                    }
                }
            }
        }
        if (permissions.bluetooth && capture.canScanBluetooth) launch {
            captureAvailable {
                scanned = true
                readings = readings.copy(nearbyDevices = 0)
                onProgress(readings)
                capture.bluetooth().collect { sighting ->
                    if (sighting.rssi >= NEARBY_RSSI_DBM && nearby.add(sighting.device)) {
                        readings = readings.copy(nearbyDevices = nearby.size)
                        onProgress(readings)
                    }
                }
            }
        }
        awaitCancellation()
    }

    return withContext(Dispatchers.Default) {
        val flicker = CameraFeatures.flicker(luminance)
        val spl = audioLevels(audio.filter { !it.dbfs })
        val dbfs = audioLevels(audio.filter { it.dbfs })
        val features = FeatureVector(
            dbMean = spl?.mean,
            dbPeak = spl?.peak,
            soundClasses = soundClasses(audio),
            luxMean = if (luxCount == 0) null else luxSum / luxCount,
            flickerIndex = flicker?.flickerIndex,
            nearbyDevices = if (scanned) nearby.size else null,
        )
        MeasurementReview(
            measurement = Measurement(
                placeId = placeId,
                lat = point.lat,
                lon = point.lon,
                timestamp = startedAt,
                durationS = MEASUREMENT_SECONDS,
                features = features,
                flickerHz = flicker?.dominantHz,
                modelVariant = audio.firstNotNullOfOrNull { it.modelVariant } ?: "none",
                device = capture.device,
                measurementId = Uuid.random().toString(),
            ),
            permissions = permissions,
            canMeasureLux = capture.canMeasureLux,
            flicker = flicker,
            dbfs = dbfs,
        )
    }
}

private suspend fun captureAvailable(collect: suspend () -> Unit) {
    try {
        collect()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        // Keep any valid aggregate samples obtained before this sensor failed.
    }
}

private fun audioLevels(windows: List<AudioSample>): AudioLevels? {
    val levels = windows.filter { it.dbMean.isFinite() && it.dbPeak.isFinite() }
    if (levels.isEmpty()) return null
    val reference = levels.maxOf { it.dbMean }
    val meanPower = levels.sumOf { 10.0.pow((it.dbMean - reference) / 10.0) } / levels.size
    return AudioLevels(reference + 10 * log10(meanPower), levels.maxOf { it.dbPeak })
}

private fun soundClasses(windows: List<AudioSample>): Map<String, Double>? {
    val samples = windows.mapNotNull { it.soundClasses }.filter { sample ->
        sample.isEmpty() || (sample.size == SoundClass.COUNT &&
            SoundClass.entries.all { sample[it.label]?.let { p -> p in 0.0..1.0 } == true })
    }
    if (samples.isEmpty()) return null
    return SoundClass.entries.associate { sound ->
        sound.label to samples.sumOf { it[sound.label] ?: 0.0 } / samples.size
    }
}

