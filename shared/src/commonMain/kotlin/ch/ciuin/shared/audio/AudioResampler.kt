package ch.ciuin.shared.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

private const val FILTER_HALF_WIDTH = 10
private const val KAISER_BETA = 5.0
private const val MAX_TABLE_STEPS_PER_UNIT = 1024

internal fun resampleToModelRate(samples: FloatArray, sampleRate: Int): FloatArray {
    if (sampleRate == AudioPreprocessor.SAMPLE_RATE || samples.isEmpty()) return samples

    val divisor = greatestCommonDivisor(sampleRate, AudioPreprocessor.SAMPLE_RATE)
    val up = AudioPreprocessor.SAMPLE_RATE / divisor
    val down = sampleRate / divisor
    val outputSize = (samples.size.toLong() * up + down - 1) / down
    require(outputSize <= Int.MAX_VALUE) { "Resampled audio exceeds the maximum array size" }

    // SciPy's default resample_poly uses a centered Kaiser-windowed sinc at this cutoff.
    val rateFactor = maxOf(up, down)
    val radius = FILTER_HALF_WIDTH.toLong() * rateFactor
    val table = ResamplingKernel(rateFactor, up)
    return FloatArray(outputSize.toInt()) { outputIndex ->
        // Visit only the input samples within the filter's support. Beyond the clip, input is zero.
        val position = outputIndex.toLong() * down
        val first = maxOf(0, (position - radius + up - 1) / up).toInt()
        val last = minOf(samples.lastIndex.toLong(), (position + radius) / up).toInt()
        var sum = 0.0
        var offset = position - first.toLong() * up
        for (inputIndex in first..last) {
            sum += samples[inputIndex] * table.coefficient(abs(offset))
            offset -= up
        }
        sum.toFloat()
    }
}

private class ResamplingKernel(rateFactor: Int, up: Int) {
    private val tableScale: Double
    private val coefficients: DoubleArray

    init {
        // Bound the table for large coprime rates by interpolating the same kernel.
        val tableRate = minOf(rateFactor, MAX_TABLE_STEPS_PER_UNIT)
        tableScale = tableRate.toDouble() / rateFactor
        val tableRadius = FILTER_HALF_WIDTH * tableRate
        coefficients = DoubleArray(tableRadius + 2)

        val windowScale = besselI0(KAISER_BETA)
        coefficients[0] = 1.0
        var sum = 1.0
        for (offset in 1..tableRadius) {
            val angle = PI * offset / tableRate
            val windowPosition = offset.toDouble() / tableRadius
            val window = besselI0(KAISER_BETA * sqrt(1 - windowPosition * windowPosition)) / windowScale
            val coefficient = sin(angle) / angle * window
            coefficients[offset] = coefficient
            sum += 2 * coefficient
        }
        // Correct for the table spacing and give the prototype filter unity DC gain.
        val gain = up * tableScale / sum
        for (offset in 0..tableRadius) coefficients[offset] *= gain
    }

    fun coefficient(offset: Long): Double {
        if (tableScale == 1.0) return coefficients[offset.toInt()]
        val position = offset * tableScale
        val index = position.toInt()
        val fraction = position - index
        return coefficients[index] + fraction * (coefficients[index + 1] - coefficients[index])
    }
}

private fun besselI0(value: Double): Double {
    val squaredHalf = value * value / 4
    var term = 1.0
    var sum = 1.0
    var order = 1.0
    while (term > sum * 1e-16) {
        term *= squaredHalf / (order * order)
        sum += term
        order += 1
    }
    return sum
}

private fun greatestCommonDivisor(first: Int, second: Int): Int {
    var a = first
    var b = second
    while (b != 0) {
        val remainder = a % b
        a = b
        b = remainder
    }
    return a
}
