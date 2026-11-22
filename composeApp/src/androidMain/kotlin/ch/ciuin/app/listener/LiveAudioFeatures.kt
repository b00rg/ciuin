package ch.ciuin.app.listener

import android.content.Context
import android.media.MediaRecorder
import ch.ciuin.shared.audio.Stage0AudioAnalyzer
import ch.ciuin.app.core.ModelVariant
import ch.ciuin.app.measure.AudioSample
import ch.ciuin.shared.model.SoundClass
import org.tensorflow.lite.Interpreter
import java.nio.channels.FileChannel
import kotlin.math.log10

/** Extracts audio-only feature windows. Use one instance from a single consumer. */
internal class LiveAudioFeatures(context: Context, audioSource: Int, private val variant: ModelVariant) : AutoCloseable {
    private val analyzer = Stage0AudioAnalyzer(AUDIO_SAMPLE_RATE)
    // Android CDD §7.5.3 microphone sensitivity at 1 kHz. The resulting levels are estimates.
    private val nominalSplOffset = when (audioSource) {
        MediaRecorder.AudioSource.UNPROCESSED -> 130.0 // 94 dB SPL at -36 dBFS
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> 112.35 // 90 dB SPL at -22.35 dBFS
        else -> error("Unexpected audio source: $audioSource")
    }
    private val input = arrayOf(FloatArray(MODEL_WINDOW_SAMPLES))
    private val output = arrayOf(FloatArray(SoundClass.COUNT))
    // The emulator's arm64 runtime faults in XNNPACK. The built-in CPU kernels match the export.
    private val interpreter = try {
        Interpreter(loadModel(context, variant), Interpreter.Options().setUseXNNPACK(false))
    } catch (error: LinkageError) {
        throw IllegalStateException("Could not load the on-device audio runtime", error)
    }

    fun extract(window: FloatArray): AudioSample {
        val metrics = analyzer.analyze(window)
        val dbMean = (metrics.levelDbfsA + nominalSplOffset).coerceAtLeast(0.0)
        val dbPeak = dbMean + peakAboveMean(window)

        val modelWindow = input[0]
        window.copyInto(modelWindow, startIndex = window.size - modelWindow.size)
        val power = modelWindow.sumOf { it.toDouble() * it } / modelWindow.size
        val soundClasses = if (power >= SILENCE_POWER) {
            try {
                interpreter.run(input, output)
                SoundClass.entries.associate { it.label to output[0][it.index].toDouble() }
            } finally {
                modelWindow.fill(0f)
            }
        } else {
            modelWindow.fill(0f)
            emptyMap()
        }
        return AudioSample(dbMean, dbPeak, soundClasses, "Student ${variant.name}")
    }

    override fun close() = interpreter.close()

    private companion object {
        const val MODEL_WINDOW_SAMPLES = 15_600
        const val PEAK_WINDOW_SAMPLES = AUDIO_SAMPLE_RATE / 8 // 125 ms RMS
        const val SILENCE_POWER = 1e-6 // -60 dBFS

        // Apply the loudest short-term RMS rise to the A-weighted mean level.
        fun peakAboveMean(window: FloatArray): Double {
            var blockPower = 0.0
            var totalPower = 0.0
            var loudestBlockPower = 0.0
            for (index in window.indices) {
                val sample = window[index].toDouble()
                blockPower += sample * sample
                if ((index + 1) % PEAK_WINDOW_SAMPLES == 0) {
                    totalPower += blockPower
                    loudestBlockPower = maxOf(loudestBlockPower, blockPower)
                    blockPower = 0.0
                }
            }
            if (totalPower == 0.0) return 0.0
            val peakToMean = (loudestBlockPower / PEAK_WINDOW_SAMPLES) / (totalPower / window.size)
            return 10.0 * log10(peakToMean)
        }

        fun loadModel(context: Context, variant: ModelVariant) = context.assets.openFd(
            "audio-student-${variant.name.lowercase()}.tflite",
        ).use { asset ->
            asset.createInputStream().use { input ->
                input.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength)
            }
        }
    }
}
