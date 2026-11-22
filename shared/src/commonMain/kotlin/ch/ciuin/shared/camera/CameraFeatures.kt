package ch.ciuin.shared.camera

import ch.ciuin.shared.signal.Radix2Fft
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sqrt

data class LuminanceSample(val timestampNanos: Long, val value: Double)

data class FlickerResult(
    val dominantHz: Double?,
    val flickerIndex: Double,
    val sampleRateHz: Double,
    val minHz: Double,
    val maxHz: Double,
)

/** Estimates flicker from camera frame luminance, in presentation order. */
object CameraFeatures {
    fun flicker(samples: List<LuminanceSample>): FlickerResult? {
        val usable = samples.filter { it.value.isFinite() && it.value >= 0 }
            .distinctBy { it.timestampNanos }
            .sortedBy { it.timestampNanos }
        if (usable.size < MIN_SAMPLES) return null

        val intervals = usable.zipWithNext { left, right -> right.timestampNanos - left.timestampNanos }
        val medianInterval = intervals.sorted()[intervals.size / 2]
        // Interpolate jitter, but never invent frames across a dropped-frame gap.
        var start = 0
        var bestStart = 0
        var bestEnd = 0
        for (end in 1..usable.size) {
            val interval = intervals.getOrNull(end - 1)
            if (interval == null || interval < medianInterval * 0.55 || interval > medianInterval * 1.6) {
                if (end - start > bestEnd - bestStart) {
                    bestStart = start
                    bestEnd = end
                }
                start = end
            }
        }
        val run = usable.subList(bestStart, bestEnd)
        if (run.size < MIN_SAMPLES) return null
        val durationNanos = run.last().timestampNanos - run.first().timestampNanos
        if (durationNanos < NANOS_PER_SECOND) return null

        val step = durationNanos.toDouble() / (run.size - 1)
        val rate = NANOS_PER_SECOND / step
        // Stay just inside Nyquist and require at least two cycles in the usable interval.
        val maxHz = rate * 0.45
        val minHz = maxOf(2.0, 2.0 * NANOS_PER_SECOND / durationNanos)
        if (maxHz <= minHz) return null
        val values = resample(run, step)
        val area = values.sum() - (values.first() + values.last()) / 2
        if (area <= 0.0) return null
        val mean = area / (values.size - 1)
        val aboveMean = values.asList().zipWithNext { left, right ->
            areaAboveMean(left - mean, right - mean)
        }.sum()
        val hz = dominantHz(values, mean, rate, minHz, maxHz)
        return FlickerResult(hz, aboveMean / area, rate, minHz, maxHz)
    }

    private fun resample(run: List<LuminanceSample>, step: Double): DoubleArray {
        val origin = run.first().timestampNanos
        var next = 1
        return DoubleArray(run.size) { index ->
            val target = index * step
            while (next < run.lastIndex && run[next].timestampNanos - origin < target) next++
            val left = run[next - 1]
            val right = run[next]
            val fraction = ((target - (left.timestampNanos - origin)) /
                (right.timestampNanos - left.timestampNanos)).coerceIn(0.0, 1.0)
            left.value + (right.value - left.value) * fraction
        }
    }

    private fun areaAboveMean(left: Double, right: Double): Double {
        if (left >= 0 && right >= 0) return (left + right) / 2
        if (left <= 0 && right <= 0) return 0.0
        val positive = maxOf(left, right)
        return positive * positive / (2 * (positive - minOf(left, right)))
    }

    private fun dominantHz(values: DoubleArray, mean: Double, rate: Double, min: Double, max: Double): Double? {
        val fftSize = 1 shl (Int.SIZE_BITS - (values.size - 1).countLeadingZeroBits())
        val real = DoubleArray(fftSize)
        val imaginary = DoubleArray(fftSize)
        val midpoint = (values.size - 1) / 2.0
        var covariance = 0.0
        var variance = 0.0
        for (index in values.indices) {
            val x = index - midpoint
            covariance += x * (values[index] - mean)
            variance += x * x
        }
        val slope = covariance / variance
        for (index in values.indices) {
            val window = 0.5 - 0.5 * cos(2.0 * PI * index / values.lastIndex)
            real[index] = (values[index] - mean - slope * (index - midpoint)) * window
        }
        Radix2Fft(fftSize).forward(real, imaginary)
        val binHz = rate / fftSize
        val firstBin = ceil(min / binHz).toInt()
        val lastBin = (max / binHz).toInt()
        if (firstBin > lastBin) return null
        val powers = DoubleArray(fftSize / 2 + 1) { real[it] * real[it] + imaginary[it] * imaginary[it] }
        val bins = firstBin..lastBin
        val peak = bins.maxBy { powers[it] }
        val noiseFloor = bins.map { powers[it] }.sorted()[bins.count() / 2]
        val relativeAmplitude = 4 * sqrt(powers[peak]) / (values.size * mean)
        if (relativeAmplitude < 0.005 || powers[peak] < 10 * noiseFloor) return null

        val left = powers[peak - 1]
        val right = powers[peak + 1]
        val curvature = left - 2 * powers[peak] + right
        val offset = if (curvature < 0) (0.5 * (left - right) / curvature).coerceIn(-0.5, 0.5) else 0.0
        return ((peak + offset) * binHz).takeIf { it in min..max }
    }

    private const val MIN_SAMPLES = 32
    private const val NANOS_PER_SECOND = 1_000_000_000L
}
