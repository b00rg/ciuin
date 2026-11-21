package ch.ciuin.parity

import android.app.Activity
import android.app.Instrumentation
import android.content.res.AssetManager
import android.os.Bundle
import ch.ciuin.shared.audio.AudioClassifier
import ch.ciuin.shared.audio.AudioPreprocessor
import ch.ciuin.shared.audio.classifyClip
import ch.ciuin.shared.model.SoundClass
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor

class AudioParity : Instrumentation() {
    private lateinit var arguments: Bundle

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val count = runModel()
            result.putString("stream", "\nProcessed $count windows\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "\n" + error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun runModel(): Int {
        val mode = checkNotNull(arguments.getString("mode"))
        check(mode in setOf("parity", "trace"))
        val version = checkNotNull(arguments.getString("version"))
        val threads = checkNotNull(arguments.getString("threads"))
        val threadLabel = when (threads) {
            "1" -> "1 thread"
            "default" -> "default thread count"
            else -> error("Expected a thread setting of 1 or default")
        }
        val assets = targetContext.assets
        val fixture = JSONObject(assets.open("expected.json").bufferedReader().use { it.readText() })
        val fixtureClasses = fixture.getJSONArray("classes")
        val labels = SoundClass.entries.map { it.label }
        check((0 until fixtureClasses.length()).map(fixtureClasses::getString) == labels)
        val clipFixtures = fixture.getJSONArray("clips")
        val names = (0 until clipFixtures.length()).map { clipFixtures.getJSONObject(it).getString("file") }
        check(names.size == 10 && assets.list("").orEmpty().filter { it.endsWith(".wav") }.sorted() == names.sorted())

        val model = File(targetContext.filesDir, "model.tflite")
        val modelSha256 = MessageDigest.getInstance("SHA-256")
            .digest(model.readBytes()).joinToString("") { "%02x".format(it) }
        val output = JSONObject()
            .put("model_sha256", modelSha256)
            .put("runtime", "LiteRT $version built-in CPU kernels ($threadLabel, XNNPACK disabled)")
            .put("classes", JSONArray(labels))
        val options = Interpreter.Options().setUseXNNPACK(false)
        if (threads == "1") options.setNumThreads(1)
        val count = Interpreter(model, options).use { interpreter ->
            interpreter.allocateTensors()
            check(interpreter.inputTensorCount == 1 && interpreter.outputTensorCount > 0)
            val input = interpreter.getInputTensor(0)
            check(input.dataType() == DataType.FLOAT32)
            check(input.shape().contentEquals(intArrayOf(1, AudioPreprocessor.WINDOW)))
            if (mode == "trace") {
                captureTrace(assets, interpreter, input, names, output)
            } else {
                classifyClips(assets, interpreter, names, output)
            }
        }
        File(targetContext.filesDir, "output.json").writeText(output.toString())
        return count
    }

    private fun classifyClips(
        assets: AssetManager,
        interpreter: Interpreter,
        names: List<String>,
        output: JSONObject,
    ): Int {
        check(interpreter.outputTensorCount == 1)
        check(interpreter.signatureKeys.contentEquals(arrayOf("serving_default")))
        check(interpreter.getSignatureInputs("serving_default").contentEquals(arrayOf("waveform")))
        check(interpreter.getSignatureOutputs("serving_default").contentEquals(arrayOf("probabilities")))
        val probabilitiesTensor = interpreter.getOutputTensor(0)
        check(probabilitiesTensor.dataType() == DataType.FLOAT32)
        check(probabilitiesTensor.shape().contentEquals(intArrayOf(1, SoundClass.COUNT)))

        val clips = JSONArray()
        var windowCount = 0
        for (name in names) {
            val samples = readPcm16(assets, name)
            val starts = AudioPreprocessor.activeWindows(samples).map { it.start }
            val windows = JSONArray()
            val classifier = object : AudioClassifier {
                override fun classify(window: FloatArray): Map<SoundClass, Float> {
                    val buffer = Array(1) { FloatArray(SoundClass.COUNT) }
                    interpreter.run(arrayOf(window), buffer)
                    val probabilities = SoundClass.entries.associateWith { buffer[0][it.index] }
                    windows.put(probabilityJson(probabilities, "probabilities")
                        .put("start_sample", starts[windows.length()]))
                    return probabilities
                }
            }
            val average = classifier.classifyClip(samples)
            check(windows.length() == starts.size) { "$name model calls" }
            windowCount += windows.length()
            clips.put(probabilityJson(average, "clip_probabilities")
                .put("file", name)
                .put("windows", windows))
        }
        output.put("clips", clips)
        return windowCount
    }

    private fun probabilityJson(probabilities: Map<SoundClass, Float>, key: String): JSONObject {
        val values = SoundClass.entries.map { probabilities.getValue(it).toDouble() }
        check(values.all { it in 0.0..1.0 } && abs(values.sum() - 1.0) <= 1e-5)
        return JSONObject().put("top_class", SoundClass.entries.maxBy { probabilities.getValue(it) }.label)
            .put(key, JSONArray(values))
    }

    private fun captureTrace(
        assets: AssetManager,
        interpreter: Interpreter,
        input: Tensor,
        names: List<String>,
        output: JSONObject,
    ): Int {
        val order = ByteOrder.nativeOrder()
        check(order == ByteOrder.LITTLE_ENDIAN)
        val records = JSONArray()
        val tensors = JSONArray().put(tensorMetadata(input, "input", 0, 0))
        val inputBuffer = ByteBuffer.allocateDirect(input.numBytes()).order(order)
        var recordBytes = input.numBytes()
        val outputs = (0 until interpreter.outputTensorCount).associateWith { index ->
            val tensor = interpreter.getOutputTensor(index)
            tensors.put(tensorMetadata(tensor, "output", index, recordBytes))
            recordBytes += tensor.numBytes()
            ByteBuffer.allocateDirect(tensor.numBytes()).order(order)
        }
        val inputs = arrayOf<Any>(inputBuffer)
        FileOutputStream(File(targetContext.filesDir, "trace.bin")).channel.use { channel ->
            for (name in names) {
                val samples = readPcm16(assets, name)
                for (window in AudioPreprocessor.activeWindows(samples)) {
                    records.put(JSONObject().put("file", name)
                        .put("start_sample", window.start)
                        .put("offset", channel.position()))
                    inputBuffer.clear()
                    inputBuffer.asFloatBuffer().put(window.samples)
                    outputs.values.forEach { it.clear() }
                    interpreter.runForMultipleInputsOutputs(inputs, outputs)
                    writeBuffer(channel, inputBuffer)
                    outputs.values.forEach { writeBuffer(channel, it) }
                }
            }
            check(channel.position() == records.length().toLong() * recordBytes)
        }
        output.put("byte_order", "little")
            .put("record_bytes", recordBytes)
            .put("tensors", tensors)
            .put("records", records)
        return records.length()
    }

    private fun tensorMetadata(tensor: Tensor, role: String, index: Int, offset: Int): JSONObject =
        JSONObject().put("role", role)
            .put("index", index)
            .put("tensor_index", tensor.index())
            .put("name", tensor.name())
            .put("dtype", tensor.dataType().name)
            .put("shape", JSONArray(tensor.shape().toList()))
            .put("nbytes", tensor.numBytes())
            .put("offset", offset)
            .put("quant_scale", tensor.quantizationParams().scale.toDouble())
            .put("quant_zero", tensor.quantizationParams().zeroPoint)

    private fun writeBuffer(channel: FileChannel, buffer: ByteBuffer) {
        buffer.clear()
        while (buffer.hasRemaining()) channel.write(buffer)
    }
    private fun readPcm16(assets: AssetManager, name: String): FloatArray {
        val wav = ByteBuffer.wrap(assets.open(name).use { it.readBytes() }).order(ByteOrder.LITTLE_ENDIAN)
        check(wav.tag() == "RIFF")
        wav.int
        check(wav.tag() == "WAVE")
        var sampleRate = 0
        var channels = 0
        while (wav.remaining() >= 8) {
            val tag = wav.tag()
            val size = wav.int
            val start = wav.position()
            check(size >= 0 && size <= wav.remaining()) { "$name invalid WAV chunk $tag" }
            when (tag) {
                "fmt " -> {
                    check(size >= 16 && wav.short.toInt() == 1) { "$name expected PCM" }
                    channels = wav.short.toInt()
                    sampleRate = wav.int
                    wav.int
                    wav.short
                    check(wav.short.toInt() == 16) { "$name expected 16-bit PCM" }
                }
                "data" -> {
                    check(channels == 1 && sampleRate == AudioPreprocessor.SAMPLE_RATE)
                    check(size % 2 == 0)
                    val pcm = ShortArray(size / 2) { wav.short }
                    return AudioPreprocessor.pcm16(pcm, sampleRate, channels)
                }
            }
            wav.position(start + size + (size and 1))
        }
        error("$name missing WAV data")
    }

    private fun ByteBuffer.tag(): String = String(ByteArray(4).also { get(it) }, Charsets.US_ASCII)
}
