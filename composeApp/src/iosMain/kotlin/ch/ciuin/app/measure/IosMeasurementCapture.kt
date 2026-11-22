package ch.ciuin.app.measure

import ch.ciuin.shared.audio.AudioPreprocessor
import ch.ciuin.shared.audio.Stage0AudioAnalyzer
import ch.ciuin.shared.model.SoundClass
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.create
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

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosMeasurementCapture(
    private val devices: IosMeasurementDevices,
    private val modelVariant: () -> String?,
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

    override fun audio() = callbackFlow {
        val variant = modelVariant()
        val operation = devices.startMicrophone(object : IosMicrophoneSink {
            private var analyzer: Stage0AudioAnalyzer? = null
            private val classifier by lazy { variant?.let(devices::createAudioClassifier) }

            override fun window(samples: NSData, sampleRate: Int) {
                if (sampleRate !in 8_000..192_000 || samples.length != (sampleRate * Float.SIZE_BYTES).toULong()) return
                val window = FloatArray(sampleRate)
                try {
                    window.usePinned { memcpy(it.addressOf(0), samples.bytes, samples.length) }
                    val meter = analyzer?.takeIf { it.sampleRateHz == sampleRate }
                        ?: Stage0AudioAnalyzer(sampleRate).also { analyzer = it }
                    val mean = meter.analyze(window).levelDbfsA.coerceAtLeast(-120.0)
                    val classes = classifier?.classifyWindow(window, sampleRate)
                    trySend(AudioSample(
                        mean,
                        mean + peakAboveMean(window),
                        classes,
                        variant?.takeIf { classes != null }?.let { "Student $it" },
                        dbfs = true,
                    ))
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

    private fun IosAudioClassifier.classifyWindow(samples: FloatArray, sampleRate: Int): Map<String, Double>? {
        val waveform = AudioPreprocessor.pcmFloat(samples, sampleRate)
        val input = waveform.copyOfRange(waveform.size - AudioPreprocessor.WINDOW, waveform.size)
        waveform.fill(0f)
        return try {
            val power = input.sumOf { it.toDouble() * it } / input.size
            // The model has no quiet class. Use the same −60 dBFS gate as Android.
            if (power < 1e-6) return null
            val output = input.usePinned { pinned ->
                classify(NSData.create(
                    bytesNoCopy = pinned.addressOf(0),
                    length = (input.size * Float.SIZE_BYTES).toULong(),
                    freeWhenDone = false,
                ))
            } ?: return null
            if (output.length != (SoundClass.COUNT * Float.SIZE_BYTES).toULong()) return null
            val probabilities = FloatArray(SoundClass.COUNT)
            probabilities.usePinned { memcpy(it.addressOf(0), output.bytes, output.length) }
            if (probabilities.any { it !in 0f..1f }) return null
            SoundClass.entries.associate { it.label to probabilities[it.index].toDouble() }
        } finally {
            input.fill(0f)
        }
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
