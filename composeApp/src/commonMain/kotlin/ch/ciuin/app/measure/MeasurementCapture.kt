package ch.ciuin.app.measure

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

internal interface MeasurementCapture {
    val device: String
    val canMeasureLux: Boolean
    suspend fun requestPermissions(): CapturePermissions
    fun camera(): Flow<CameraSample>
    fun lux(): Flow<Double>
    fun audio(): Flow<AudioSample>

    /** Bluetooth LE scanning available and switched on (crowd proxy). Platforms without it keep the default. */
    val canScanBluetooth: Boolean get() = false

    /** Nearby Bluetooth LE advertisements during the measurement. Platforms without it keep the default. */
    fun bluetooth(): Flow<BleSighting> = emptyFlow()

    /** Live loudness in 0..1 while measuring, for the waveform. Display only; never part of the measurement. */
    fun audioLevels(): Flow<Float> = emptyFlow()
}

internal data class CapturePermissions(val camera: Boolean, val microphone: Boolean, val bluetooth: Boolean = false)

/** The camera only measures light (brightness + flicker). It never looks for people. */
internal sealed interface CameraSample {
    data class Luminance(val timestampNanos: Long, val value: Double) : CameraSample
}

/**
 * One Bluetooth LE advertisement. [device] is an in-memory fingerprint to avoid counting a device twice; the
 * device address is never read, stored or sent. Only the final count leaves the phone.
 */
internal data class BleSighting(val device: Int, val rssi: Int)

internal data class AudioSample(
    val dbMean: Double,
    val dbPeak: Double,
    val soundClasses: Map<String, Double>?,
    val modelVariant: String?,
    val dbfs: Boolean = false,
)
