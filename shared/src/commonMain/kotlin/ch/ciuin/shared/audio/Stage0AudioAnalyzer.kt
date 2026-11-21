package ch.ciuin.shared.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class Stage0AudioMetrics(
    val levelDbfsA: Double,
    val spectralFlux: Double,
)

/**
 * Measures consecutive, non-overlapping one-second windows at the capture sample rate.
 * Use one analyzer per recording on a single thread. Call [reset] after a recording break.
 *
 * The A-weighted RMS level uses full-scale sample RMS (1.0) as its reference and is
 * negative infinity at zero weighted power. Spectral flux is the L2 norm of the
 * positive increases in unweighted, one-sided RMS bin magnitudes. The first window
 * has zero flux. Silence replaces the previous spectrum with zeros, so the next
 * sound registers as an onset.
 */
class Stage0AudioAnalyzer(val sampleRateHz: Int) {
    init {
        require(sampleRateHz in 1..(1 shl 30)) { "Invalid sample rate: $sampleRateHz Hz" }
    }

    private val fftSize = 1 shl (Int.SIZE_BITS - (sampleRateHz - 1).countLeadingZeroBits())
    private val fft = Radix2Fft(fftSize)
    private val real = DoubleArray(fftSize)
    private val imaginary = DoubleArray(fftSize)
    private val nyquistBin = fftSize / 2
    private val spectrumScale = sqrt(2.0 / (fftSize.toDouble() * sampleRateHz))
    private val nyquistScale = sqrt(1.0 / (fftSize.toDouble() * sampleRateHz))
    private val aGains = DoubleArray(nyquistBin + 1) { bin ->
        aWeightingGain(bin.toDouble() * sampleRateHz / fftSize)
    }
    private val previousMagnitudes = DoubleArray(nyquistBin + 1)
    private var hasPreviousWindow = false

    /** [window] contains exactly [sampleRateHz] finite mono samples in [-1, 1]. */
    fun analyze(window: FloatArray): Stage0AudioMetrics {
        require(window.size == sampleRateHz) { "Expected $sampleRateHz samples for one second" }
        val mean = window.sumOf { sample ->
            require(sample in -1f..1f) { "Expected finite mono samples in [-1, 1]" }
            sample.toDouble()
        } / window.size

        // Remove DC before zero-padding so a constant offset cannot leak into other bins.
        for (index in window.indices) real[index] = window[index] - mean
        real.fill(0.0, fromIndex = window.size)
        imaginary.fill(0.0)
        fft.forward(real, imaginary)

        var weightedPower = 0.0
        var fluxSquared = 0.0
        for (bin in 1..nyquistBin) {
            // Count each positive-frequency bin's negative-frequency partner once.
            val scale = if (bin == nyquistBin) nyquistScale else spectrumScale
            val magnitude = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin]) * scale
            val weightedMagnitude = magnitude * aGains[bin]
            weightedPower += weightedMagnitude * weightedMagnitude

            val increase = (magnitude - previousMagnitudes[bin]).coerceAtLeast(0.0)
            fluxSquared += increase * increase
            previousMagnitudes[bin] = magnitude
        }

        val flux = if (hasPreviousWindow) sqrt(fluxSquared) else 0.0
        hasPreviousWindow = true
        return Stage0AudioMetrics(10.0 * log10(weightedPower), flux)
    }

    /** Forget the previous window so the next window starts a new recording. */
    fun reset() {
        hasPreviousWindow = false
    }
}

private fun aWeightingGain(frequencyHz: Double): Double {
    // IEC A-weighting magnitude response with the +2 dB normalization near 1 kHz.
    val fSquared = frequencyHz * frequencyHz
    val highPoleSquared = 12194.217 * 12194.217
    return 10.0.pow(2.0 / 20.0) * highPoleSquared * fSquared * fSquared /
        ((fSquared + 20.598997 * 20.598997) *
            sqrt((fSquared + 107.65265 * 107.65265) * (fSquared + 737.86223 * 737.86223)) *
            (fSquared + highPoleSquared))
}

private class Radix2Fft(private val size: Int) {
    private val reversedIndices = IntArray(size).also { indices ->
        for (index in 1 until size) {
            indices[index] = (indices[index shr 1] shr 1) or ((index and 1) * (size / 2))
        }
    }
    private val cosines = DoubleArray(size / 2) { cos(-2.0 * PI * it / size) }
    private val sines = DoubleArray(size / 2) { sin(-2.0 * PI * it / size) }

    fun forward(real: DoubleArray, imaginary: DoubleArray) {
        // Input is real, so the imaginary buffer starts at zero and needs no permutation.
        for (index in reversedIndices.indices) {
            val reversed = reversedIndices[index]
            if (index < reversed) {
                val sample = real[index]
                real[index] = real[reversed]
                real[reversed] = sample
            }
        }

        var halfSize = 1
        while (halfSize < size) {
            val blockSize = 2 * halfSize
            val twiddleStep = size / blockSize
            for (start in 0 until size step blockSize) {
                for (offset in 0 until halfSize) {
                    val even = start + offset
                    val odd = even + halfSize
                    val twiddle = offset * twiddleStep
                    val cos = cosines[twiddle]
                    val sin = sines[twiddle]
                    val oddReal = cos * real[odd] - sin * imaginary[odd]
                    val oddImaginary = sin * real[odd] + cos * imaginary[odd]
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                }
            }
            halfSize = blockSize
        }
    }
}
