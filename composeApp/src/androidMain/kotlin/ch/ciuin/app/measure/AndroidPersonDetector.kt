package ch.ciuin.app.measure

import android.content.Context
import android.media.Image
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

private const val MODEL_SIZE = 300
private const val MAX_DETECTIONS = 10
private const val PERSON_CLASS = 0f
private const val MIN_CONFIDENCE = 0.50f

internal class AndroidPersonDetector(context: Context) : AutoCloseable {
    private val interpreter = context.assets.openFd("person-mobilenet-ssd.tflite").use { asset ->
        val model = asset.createInputStream().use { input ->
            input.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength)
        }
        Interpreter(model, Interpreter.Options().setNumThreads(2).setUseXNNPACK(false))
    }
    private val input = ByteBuffer.allocateDirect(MODEL_SIZE * MODEL_SIZE * 3).order(ByteOrder.nativeOrder())
    private val classes = Array(1) { FloatArray(MAX_DETECTIONS) }
    private val scores = Array(1) { FloatArray(MAX_DETECTIONS) }
    private val detectionCount = FloatArray(1)
    private val outputs = mapOf(1 to classes, 2 to scores, 3 to detectionCount)

    fun count(rgb: ByteArray): Int {
        input.clear()
        input.put(rgb)
        input.rewind()
        try {
            interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)
            return (0 until detectionCount[0].toInt().coerceIn(0, MAX_DETECTIONS)).count { i ->
                classes[0][i] == PERSON_CLASS && scores[0][i] >= MIN_CONFIDENCE
            }
        } finally {
            input.clear()
            while (input.remaining() >= Long.SIZE_BYTES) input.putLong(0L)
            while (input.hasRemaining()) input.put(0)
            input.rewind()
        }
    }

    override fun close() = interpreter.close()
}

internal fun Image.meanLuminance(): Double {
    val plane = planes[0]
    val data = plane.buffer
    val start = data.position()
    var sum = 0L
    var count = 0
    for (row in 0 until height step 8) {
        for (column in 0 until width step 8) {
            sum += data.get(start + row * plane.rowStride + column * plane.pixelStride).toInt() and 0xff
            count++
        }
    }
    return sum.toDouble() / count
}

internal fun Image.personDetectorInput(rotation: Int): ByteArray {
    val luma = planes[0]
    val cb = planes[1]
    val cr = planes[2]
    val lumaBuffer = luma.buffer
    val cbBuffer = cb.buffer
    val crBuffer = cr.buffer
    val rgb = ByteArray(MODEL_SIZE * MODEL_SIZE * 3)
    val rotatedWidth = if (rotation == 90 || rotation == 270) height else width
    val rotatedHeight = if (rotation == 90 || rotation == 270) width else height
    val scale = MODEL_SIZE.toDouble() / maxOf(rotatedWidth, rotatedHeight)
    val scaledWidth = (rotatedWidth * scale).roundToInt()
    val scaledHeight = (rotatedHeight * scale).roundToInt()
    val left = (MODEL_SIZE - scaledWidth) / 2
    val top = (MODEL_SIZE - scaledHeight) / 2

    for (row in 0 until scaledHeight) {
        val y = (row / scale).toInt().coerceAtMost(rotatedHeight - 1)
        var index = ((top + row) * MODEL_SIZE + left) * 3
        for (column in 0 until scaledWidth) {
            val x = (column / scale).toInt().coerceAtMost(rotatedWidth - 1)
            val sourceX = when (rotation) {
                90 -> y
                180 -> width - 1 - x
                270 -> width - 1 - y
                else -> x
            }
            val sourceY = when (rotation) {
                90 -> height - 1 - x
                180 -> height - 1 - y
                270 -> x
                else -> y
            }
            val lumaIndex = lumaBuffer.position() + sourceY * luma.rowStride + sourceX * luma.pixelStride
            val cbIndex = cbBuffer.position() + (sourceY / 2) * cb.rowStride + (sourceX / 2) * cb.pixelStride
            val crIndex = crBuffer.position() + (sourceY / 2) * cr.rowStride + (sourceX / 2) * cr.pixelStride
            val light = ((lumaBuffer.get(lumaIndex).toInt() and 0xff) - 16).coerceAtLeast(0) * 298
            val blue = (cbBuffer.get(cbIndex).toInt() and 0xff) - 128
            val red = (crBuffer.get(crIndex).toInt() and 0xff) - 128
            rgb[index++] = ((light + 409 * red + 128) shr 8).coerceIn(0, 255).toByte()
            rgb[index++] = ((light - 100 * blue - 208 * red + 128) shr 8).coerceIn(0, 255).toByte()
            rgb[index++] = ((light + 516 * blue + 128) shr 8).coerceIn(0, 255).toByte()
        }
    }
    return rgb
}
