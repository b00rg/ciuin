package ch.ciuin.shared.audio

import kotlin.math.log10

/** A model input and its start sample in the 16 kHz mono clip. */
class AudioWindow internal constructor(val start: Int, val samples: FloatArray)

/** Prepares waveforms for the audio model using the contract in ml/export/preprocess.md. */
object AudioPreprocessor {
    const val SAMPLE_RATE = 16_000
    const val WINDOW = 15_600
    const val HOP = 8_000
    private const val SILENCE_DBFS = -60.0

    /** Converts interleaved signed 16-bit PCM to 16 kHz mono float in [-1, 1]. */
    fun pcm16(samples: ShortArray, sampleRate: Int, channels: Int = 1): FloatArray =
        prepare(samples.size, sampleRate, channels) { samples[it] / 32_768f }

    /** Converts interleaved signed 24-bit PCM, sign-extended into Int without a left shift. */
    fun pcm24(samples: IntArray, sampleRate: Int, channels: Int = 1): FloatArray =
        prepare(samples.size, sampleRate, channels) { samples[it] / 8_388_608f }

    /** Converts interleaved signed 32-bit PCM to 16 kHz mono float in [-1, 1]. */
    fun pcm32(samples: IntArray, sampleRate: Int, channels: Int = 1): FloatArray =
        prepare(samples.size, sampleRate, channels) { samples[it] / 2_147_483_648f }

    /** Downmixes interleaved float PCM, resamples to 16 kHz, and clamps to [-1, 1]. */
    fun pcmFloat(samples: FloatArray, sampleRate: Int, channels: Int = 1): FloatArray =
        prepare(samples.size, sampleRate, channels) { samples[it] }

    /**
     * Selects the model inputs for a prepared mono clip. The final window ends at the clip's
     * end, and a short clip is zero-padded. When every window is quiet, keeps the first loudest.
     */
    fun activeWindows(samples: FloatArray): List<AudioWindow> {
        val lastStart = (samples.size - WINDOW).coerceAtLeast(0)
        val starts = (0..lastStart step HOP).toMutableList()
        if (starts.last() != lastStart) starts += lastStart

        val active = mutableListOf<AudioWindow>()
        var loudestStart = 0
        var loudestLevel = Double.NEGATIVE_INFINITY
        for (start in starts) {
            val level = levelDbfs(samples, start)
            if (level >= SILENCE_DBFS) active += window(samples, start)
            if (level > loudestLevel) {
                loudestStart = start
                loudestLevel = level
            }
        }
        return active.ifEmpty { listOf(window(samples, loudestStart)) }
    }

    private inline fun prepare(
        sampleCount: Int,
        sampleRate: Int,
        channels: Int,
        sampleAt: (Int) -> Float,
    ): FloatArray {
        require(sampleRate > 0) { "Sample rate must be positive" }
        require(channels > 0 && sampleCount % channels == 0) { "PCM must contain whole frames" }

        val mono = FloatArray(sampleCount / channels) { frame ->
            val offset = frame * channels
            var sum = 0.0
            for (channel in 0 until channels) sum += sampleAt(offset + channel)
            (sum / channels).toFloat()
        }
        val waveform = resampleToModelRate(mono, sampleRate)
        for (index in waveform.indices) waveform[index] = waveform[index].coerceIn(-1f, 1f)
        return waveform
    }

    private fun window(samples: FloatArray, start: Int): AudioWindow = AudioWindow(
        start,
        if (samples.size <= WINDOW) samples.copyOf(WINDOW) else samples.copyOfRange(start, start + WINDOW),
    )

    private fun levelDbfs(samples: FloatArray, start: Int): Double {
        var sumSquares = 0.0
        for (index in start until minOf(samples.size, start + WINDOW)) {
            val sample = samples[index].toDouble()
            sumSquares += sample * sample
        }
        return 10 * log10(sumSquares / WINDOW + 1e-12)
    }
}
