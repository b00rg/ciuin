package ch.ciuin.app.measure

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.UIKit.UIDevice
import kotlin.coroutines.resume

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
    fun level(value: Float)
    fun ended()
}

interface IosPermissionSink {
    fun complete(camera: Boolean, microphone: Boolean)
}

interface IosAudioClassifier {
    /** Consumes a borrowed Float32 model window synchronously and returns nine Float32 probabilities. */
    fun classify(window: NSData): NSData?
}

interface IosMeasurementDevices {
    fun requestPermissions(sink: IosPermissionSink)
    fun startCamera(sink: IosCameraSink): IosMeasurementOperation
    fun startMicrophone(sink: IosMicrophoneSink): IosMeasurementOperation
    fun supportsAudioClassifier(modelVariant: String): Boolean
    fun createAudioClassifier(modelVariant: String): IosAudioClassifier?
    fun stopAll()
}

internal class IosMeasurementCapture(
    private val devices: IosMeasurementDevices,
    private val audioCapture: IosAudioCapture,
) : MeasurementCapture {
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

    override fun audio() = audioCapture.windows.map { it.getOrThrow() }

    override fun audioLevels() = audioCapture.levels
}
