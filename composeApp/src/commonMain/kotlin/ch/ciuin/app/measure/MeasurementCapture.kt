package ch.ciuin.app.measure

import kotlinx.coroutines.flow.Flow

internal interface MeasurementCapture {
    val device: String
    val canMeasureLux: Boolean
    suspend fun requestPermissions(): CapturePermissions
    fun camera(): Flow<CameraSample>
    fun lux(): Flow<Double>
    fun audio(): Flow<AudioSample>
}

internal data class CapturePermissions(val camera: Boolean, val microphone: Boolean)

internal sealed interface CameraSample {
    data class Luminance(val timestampNanos: Long, val value: Double) : CameraSample
    data class People(val count: Int, val inferenceMs: Double) : CameraSample
}

internal data class AudioSample(
    val dbMean: Double,
    val dbPeak: Double,
    val soundClasses: Map<String, Double>?,
    val modelVariant: String?,
    val dbfs: Boolean = false,
)
