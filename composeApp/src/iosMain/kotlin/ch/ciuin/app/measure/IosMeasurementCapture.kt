package ch.ciuin.app.measure

import ch.ciuin.shared.audio.Stage0AudioAnalyzer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.UIKit.UIDevice
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.math.log10

interface IosMeasurementOperation {
    fun stop()
}

interface IosCameraSink {
    fun luminance(timestampNanos: Long, value: Double)
    fun people(count: Int, inferenceMs: Double)
    fun ended()
}

interface IosMicrophoneSink {
    fun window(samples: NSData, sampleRate: Int)
    fun ended()
}

interface IosPermissionSink {
    fun complete(camera: Boolean, microphone: Boolean)
}

interface IosMeasurementDevices {
    fun requestPermissions(sink: IosPermissionSink)
    fun startCamera(sink: IosCameraSink): IosMeasurementOperation
    fun startMicrophone(sink: IosMicrophoneSink): IosMeasurementOperation
    fun stopAll()
}

@OptIn(ExperimentalForeignApi::class)
internal class IosMeasurementCapture(private val devices: IosMeasurementDevices) : MeasurementCapture {
    override val device = "iOS ${UIDevice.currentDevice.model}"
    override val canMeasureLux = false

    override suspend fun requestPermissions(): CapturePermissions = suspendCancellableCoroutine { continuation ->
        devices.requestPermissions(object : IosPermissionSink {
            override fun complete(camera: Boolean, microphone: Boolean) {
                continuation.resume(CapturePermissions(camera, microphone))
            }
        })
    }

    override fun lux() = emptyFlow<Double>()

    override fun camera() = callbackFlow {
        val operation = devices.startCamera(object : IosCameraSink {
            override fun luminance(timestampNanos: Long, value: Double) {
                trySend(CameraSample.Luminance(timestampNanos, value))
            }

            override fun people(count: Int, inferenceMs: Double) {
                // People counting was removed (crowd now comes from a Bluetooth scan). Kept for the Swift API.
            }

            override fun ended() {
                close()
            }
        })
        awaitClose(operation::stop)
    }

    override fun audio() = callbackFlow {
        val operation = devices.startMicrophone(object : IosMicrophoneSink {
            private var analyzer: Stage0AudioAnalyzer? = null

            override fun window(samples: NSData, sampleRate: Int) {
                if (sampleRate !in 8_000..192_000 || samples.length != (sampleRate * Float.SIZE_BYTES).toULong()) return
                val window = FloatArray(sampleRate)
                try {
                    window.usePinned { memcpy(it.addressOf(0), samples.bytes, samples.length) }
                    val meter = analyzer?.takeIf { it.sampleRateHz == sampleRate }
                        ?: Stage0AudioAnalyzer(sampleRate).also { analyzer = it }
                    val mean = meter.analyze(window).levelDbfsA.coerceAtLeast(-120.0)
                    trySend(AudioSample(mean, mean + peakAboveMean(window), null, null, dbfs = true))
                } finally {
                    window.fill(0f)
                }
            }

            override fun ended() {
                close()
            }
        })
        awaitClose(operation::stop)
    }
}

private fun peakAboveMean(window: FloatArray): Double {
    var totalPower = 0.0
    var peak = 0.0
    for (block in 0 until 8) {
        val first = block * window.size / 8
        val last = (block + 1) * window.size / 8
        var blockPower = 0.0
        for (index in first until last) {
            val sample = window[index].toDouble()
            blockPower += sample * sample
        }
        totalPower += blockPower
        peak = maxOf(peak, blockPower / (last - first))
    }
    return if (totalPower > 0.0) 10.0 * log10(peak / (totalPower / window.size)) else 0.0
}
