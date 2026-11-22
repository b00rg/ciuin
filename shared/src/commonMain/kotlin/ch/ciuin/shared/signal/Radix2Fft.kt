package ch.ciuin.shared.signal

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal class Radix2Fft(private val size: Int) {
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
