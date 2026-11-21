package ch.ciuin.parity

import android.content.res.AssetManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import ch.ciuin.shared.audio.AudioPreprocessor
import ch.ciuin.shared.model.SoundClass
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter

private const val REFERENCE_TOLERANCE = 1e-6

private class BenchmarkWindow(
    val clip: String,
    val start: Int,
    waveform: FloatArray,
    val expected: DoubleArray,
) {
    val input = arrayOf(waveform)
    val actual = FloatArray(SoundClass.COUNT)
}

internal fun benchmarkModel(
    model: File,
    options: Interpreter.Options,
    assets: AssetManager,
    fixture: JSONObject,
    arguments: Bundle,
    runtime: String,
): JSONObject {
    val warmupPasses = arguments.getString("warmup_passes", "3").toInt()
    val measuredPasses = arguments.getString("measured_passes", "20").toInt()
    check(warmupPasses >= 0 && measuredPasses > 0)
    val modelHash = checkNotNull(arguments.getString("model_sha256"))
    check(modelHash.matches(Regex("[0-9a-f]{64}")))
    val referenceSource = arguments.getString("reference", "device")
    check(referenceSource == "device")
    val reference = JSONObject(File(model.parentFile, "reference.json").readText())
    check(reference.getString("model_sha256") == modelHash) { "Reference model SHA-256 mismatch" }
    val classes = fixture.getJSONArray("classes")
    val referenceClasses = reference.getJSONArray("classes")
    check(referenceClasses.length() == classes.length())
    check((0 until classes.length()).all { referenceClasses.getString(it) == classes.getString(it) })

    val windows = prepareWindows(assets, fixture.getJSONArray("clips"), reference.getJSONArray("clips"))
    check(windows.size == 65) { "Expected the 65 handoff windows, got ${windows.size}" }
    val warmupDurations = LongArray(Math.multiplyExact(windows.size, warmupPasses))
    val measuredDurations = LongArray(Math.multiplyExact(windows.size, measuredPasses))
    val outputBuffer = arrayOf(FloatArray(SoundClass.COUNT))
    val beforeLoad = processMemory()

    val loadStarted = SystemClock.elapsedRealtimeNanos()
    val measurements = Interpreter(model, options).use { interpreter ->
        interpreter.allocateTensors()
        val loadNs = SystemClock.elapsedRealtimeNanos() - loadStarted
        val afterAllocate = processMemory()
        checkWaveformInput(interpreter)
        checkProbabilityOutput(interpreter)

        val warmupError = runPasses(interpreter, windows, outputBuffer, warmupDurations, captureFirstPass = true)
        val processCpuStarted = Process.getElapsedCpuTime()
        val threadCpuStarted = Debug.threadCpuTimeNanos()
        val loopStarted = SystemClock.elapsedRealtimeNanos()
        val measuredError = runPasses(
            interpreter, windows, outputBuffer, measuredDurations, captureFirstPass = warmupPasses == 0,
        )
        val loopWallNs = SystemClock.elapsedRealtimeNanos() - loopStarted
        val loopThreadCpuNs = Debug.threadCpuTimeNanos() - threadCpuStarted
        val loopProcessCpuMs = Process.getElapsedCpuTime() - processCpuStarted
        val afterInference = processMemory()

        JSONObject()
            .put("load_ns", loadNs)
            .put("loop_wall_ns", loopWallNs)
            .put("loop_thread_cpu_ns", loopThreadCpuNs)
            .put("loop_process_cpu_ms", loopProcessCpuMs)
            .put("memory", JSONObject()
                .put("before_load", beforeLoad)
                .put("after_allocate", afterAllocate)
                .put("after_inference", afterInference))
            .put("max_reference_delta", maxOf(warmupError, measuredError))
    }
    val benchmark = measurements
        .put("pid", Process.myPid())
        .put("process_start_elapsed_ms", Process.getStartElapsedRealtime())
        .put("android_build", Build.FINGERPRINT)
        .put("warmup_passes", warmupPasses)
        .put("measured_passes", measuredPasses)
        .put("windows_per_pass", windows.size)
        .put("warmup_inferences", warmupDurations.size)
        .put("measured_inferences", measuredDurations.size)
        .put("warmup_inference_ns", JSONArray(warmupDurations.toList()))
        .put("inference_ns", JSONArray(measuredDurations.toList()))
        .put("reference", referenceSource)
        .put("output_sha256", hashWindowOutputs(windows))
    return JSONObject()
        .put("model_sha256", modelHash)
        .put("runtime", runtime)
        .put("classes", classes)
        .put("clips", reportClips(windows))
        .put("benchmark", benchmark)
}

private fun prepareWindows(assets: AssetManager, fixtures: JSONArray, references: JSONArray): List<BenchmarkWindow> {
    check(fixtures.length() == references.length())
    return buildList {
        for (clipIndex in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(clipIndex)
            val reference = references.getJSONObject(clipIndex)
            val name = fixture.getString("file")
            check(reference.getString("file") == name)
            val windows = AudioPreprocessor.activeWindows(readPcm16(assets, name))
            val fixtureWindows = fixture.getJSONArray("windows")
            val referenceWindows = reference.getJSONArray("windows")
            check(windows.size == fixtureWindows.length() && windows.size == referenceWindows.length())
            for ((windowIndex, window) in windows.withIndex()) {
                val oracle = referenceWindows.getJSONObject(windowIndex)
                val expectedStart = fixtureWindows.getJSONObject(windowIndex).getInt("start_sample")
                check(window.start == expectedStart && window.start == oracle.getInt("start_sample"))
                val probabilities = oracle.getJSONArray("probabilities")
                check(probabilities.length() == SoundClass.COUNT)
                val expected = DoubleArray(SoundClass.COUNT, probabilities::getDouble)
                check(expected.all { it in 0.0..1.0 } && abs(expected.sum() - 1.0) <= 1e-5)
                add(BenchmarkWindow(name, window.start, window.samples, expected))
            }
        }
    }
}

private fun runPasses(
    interpreter: Interpreter,
    windows: List<BenchmarkWindow>,
    outputBuffer: Array<FloatArray>,
    durations: LongArray,
    captureFirstPass: Boolean,
): Double {
    val probabilities = outputBuffer[0]
    var maxDelta = 0.0
    repeat(durations.size / windows.size) { pass ->
        for (windowIndex in windows.indices) {
            val window = windows[windowIndex]
            probabilities.fill(Float.NaN)
            val inferenceStarted = SystemClock.elapsedRealtimeNanos()
            interpreter.run(window.input, outputBuffer)
            val inferenceNs = SystemClock.elapsedRealtimeNanos() - inferenceStarted
            durations[pass * windows.size + windowIndex] = inferenceNs

            var sum = 0.0
            for (index in probabilities.indices) {
                val value = probabilities[index].toDouble()
                val delta = abs(value - window.expected[index])
                check(value in 0.0..1.0 && delta <= REFERENCE_TOLERANCE) {
                    "${window.clip} start=${window.start} class=$index delta=$delta"
                }
                sum += value
                maxDelta = maxOf(maxDelta, delta)
            }
            check(abs(sum - 1.0) <= 1e-5)
            if (captureFirstPass && pass == 0) {
                probabilities.copyInto(window.actual)
            } else {
                check(probabilities.contentEquals(window.actual)) {
                    "${window.clip} start=${window.start} is not deterministic"
                }
            }
        }
    }
    return maxDelta
}

private fun processMemory(): JSONObject {
    val status = File("/proc/self/status").readLines()
    fun kib(label: String): Long {
        val value = status.single { it.startsWith(label) }.substringAfter(':').trim()
        check(value.endsWith(" kB")) { "Unrecognised process memory value for $label" }
        return value.removeSuffix(" kB").trim().toLong()
    }
    return JSONObject()
        .put("vm_hwm_kib", kib("VmHWM:"))
        .put("vm_rss_kib", kib("VmRSS:"))
}

private fun hashWindowOutputs(windows: List<BenchmarkWindow>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteBuffer.allocate(SoundClass.COUNT * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    for (window in windows) {
        buffer.clear()
        for (value in window.actual) buffer.putFloat(value)
        digest.update(buffer.array())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun reportClips(windows: List<BenchmarkWindow>): JSONArray {
    val clips = JSONArray()
    for ((name, clipWindows) in windows.groupBy { it.clip }) {
        val sums = DoubleArray(SoundClass.COUNT)
        val reportWindows = JSONArray()
        for (window in clipWindows) {
            for (index in sums.indices) sums[index] += window.actual[index].toDouble()
            val values = SoundClass.entries.associateWith { window.actual[it.index] }
            reportWindows.put(probabilityJson(values, "probabilities").put("start_sample", window.start))
        }
        val average = SoundClass.entries.associateWith { (sums[it.index] / clipWindows.size).toFloat() }
        clips.put(probabilityJson(average, "clip_probabilities").put("file", name).put("windows", reportWindows))
    }
    return clips
}
