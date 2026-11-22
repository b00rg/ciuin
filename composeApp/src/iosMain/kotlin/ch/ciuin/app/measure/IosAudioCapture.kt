package ch.ciuin.app.measure

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.ciuin.app.core.ModelVariant
import ch.ciuin.app.core.SettingsController
import ch.ciuin.app.listener.EscalationRule
import ch.ciuin.app.listener.SoundClassSmoother
import ch.ciuin.shared.audio.AudioPreprocessor
import ch.ciuin.shared.audio.Stage0AudioAnalyzer
import ch.ciuin.shared.model.SoundClass
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.shareIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.posix.memcpy
import kotlin.math.log10

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosAudioCapture(
    devices: IosMeasurementDevices,
    settings: SettingsController,
    scope: CoroutineScope,
) {
    val availableModels = listOf(ModelVariant.S, ModelVariant.M)
        .filter { devices.supportsAudioClassifier(it.name) }.toSet()

    private val cascadeConfig = NSBundle.mainBundle.pathForResource("cascade", "json")?.let { path ->
        NSString.create(contentsOfFile = path, encoding = NSUTF8StringEncoding, error = null)?.toString()
    }?.let { Json.parseToJsonElement(it).jsonObject }
    private val stage1To2 = cascadeConfig?.get("stage1_to_2")?.jsonObject
    private val escalationMargin = stage1To2?.get("models")?.jsonObject
        ?.get("audio-student-s")?.jsonObject?.get("threshold")?.jsonPrimitive?.doubleOrNull
        ?: EscalationRule.DEFAULT_MARGIN
    val liveSmoothingWindows = cascadeConfig?.get("live_smoothing")?.jsonObject
        ?.get("windows")?.jsonPrimitive?.intOrNull ?: SoundClassSmoother.DEFAULT_WINDOWS
    val overwhelmThreshold = stage1To2?.get("p_overwhelm_threshold")?.jsonPrimitive?.doubleOrNull ?: 0.5

    var activeVariant by mutableStateOf<ModelVariant?>(null)
        private set

    val levels = MutableSharedFlow<Float>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // Measure and live listening share one microphone and one model selection per capture.
    val windows = callbackFlow<Result<AudioSample>> {
        settings.awaitLoaded()
        val variant = settings.modelVariant.takeIf { it in availableModels } ?: availableModels.firstOrNull()
        val analyzer = IosAudioWindowAnalyzer(devices, variant?.name, escalationMargin)
        activeVariant = variant
        try {
            val operation = devices.startMicrophone(object : IosMicrophoneSink {
                override fun window(samples: NSData, sampleRate: Int) {
                    val result = analyzer.analyze(samples, sampleRate) ?: return
                    trySend(Result.success(AudioSample(
                        result.dbMean, result.dbPeak, result.soundClasses, result.modelVariant, dbfs = true,
                    )))
                }

                override fun level(value: Float) {
                    levels.tryEmit(value)
                }

                override fun ended() {
                    close(IllegalStateException("Microphone capture ended"))
                }
            })
            awaitClose(operation::stop)
        } finally {
            activeVariant = null
        }
    }.catch { emit(Result.failure(it)) }.buffer(0).shareIn(scope, SharingStarted.WhileSubscribed())
}

class IosAnalyzedAudio internal constructor(
    val dbMean: Double,
    val dbPeak: Double,
    val soundClasses: Map<String, Double>?,
    val modelVariant: String?,
)

/** Analyzes one-second microphone windows. Use one instance from one serial audio consumer. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosAudioWindowAnalyzer(
    private val devices: IosMeasurementDevices,
    private val modelVariant: String?,
    escalationMargin: Double,
) {
    private var meter: Stage0AudioAnalyzer? = null
    private val escalation = EscalationRule(escalationMargin)
    private val primary by lazy { modelVariant?.let(devices::createAudioClassifier) }
    private val stage2 by lazy { devices.createAudioClassifier("M") }

    fun analyze(samples: NSData, sampleRate: Int): IosAnalyzedAudio? {
        if (sampleRate !in 8_000..192_000 || samples.length != (sampleRate * Float.SIZE_BYTES).toULong()) return null
        val window = FloatArray(sampleRate)
        try {
            window.usePinned { memcpy(it.addressOf(0), samples.bytes, samples.length) }
            val analyzer = meter?.takeIf { it.sampleRateHz == sampleRate }
                ?: Stage0AudioAnalyzer(sampleRate).also { meter = it }
            val mean = analyzer.analyze(window).levelDbfsA.coerceAtLeast(-120.0)
            val peak = mean + peakAboveMean(window)
            val variant = modelVariant ?: return IosAnalyzedAudio(mean, peak, null, null)
            val waveform = AudioPreprocessor.pcmFloat(window, sampleRate)
            val input = waveform.copyOfRange(waveform.size - AudioPreprocessor.WINDOW, waveform.size)
            waveform.fill(0f)
            try {
                val power = input.sumOf { it.toDouble() * it } / input.size
                // The models have no quiet class. Match Android's -60 dBFS gate.
                if (power < 1e-6) return IosAnalyzedAudio(mean, peak, emptyMap(), "Student $variant")
                val probabilities = primary?.probabilities(input)
                    ?: return IosAnalyzedAudio(mean, peak, null, null)
                val escalated = if (variant == "S" && escalation.shouldEscalate(probabilities)) {
                    stage2?.probabilities(input)
                } else null
                val output = escalated ?: probabilities
                val classes = SoundClass.entries.associate { it.label to output[it.index].toDouble() }
                return IosAnalyzedAudio(mean, peak, classes, if (escalated != null) "Student M" else "Student $variant")
            } finally {
                input.fill(0f)
            }
        } finally {
            window.fill(0f)
        }
    }

    private fun IosAudioClassifier.probabilities(input: FloatArray): FloatArray? {
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
        return probabilities.takeIf { it.all { probability -> probability in 0f..1f } }
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
