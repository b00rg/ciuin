package ch.ciuin.shared.audio

import ch.ciuin.shared.model.SoundClass
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class AudioParityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun handoffClipsMatchStudentOutputs() {
        val python = System.getenv("CIUIN_LITERT_PYTHON").orEmpty()
        assumeTrue("Set CIUIN_LITERT_PYTHON to run the audio model parity test", python.isNotBlank())

        val export = File("../ml/export")
        val clips = export.resolve("test_clips")
        val model = export.resolve("audio-student-s.tflite")
        val expected = json.decodeFromString<ExpectedHandoff>(clips.resolve("expected.json").readText())

        assertEquals(model.name, expected.model)
        assertEquals(SoundClass.entries.map { it.label }, expected.classes)
        assertEquals(10, expected.clips.size)
        assertEquals(
            clips.listFiles().orEmpty().filter { it.extension == "wav" }.map { it.name }.sorted(),
            expected.clips.map { it.file }.sorted(),
            "WAV fixture inventory",
        )

        LiteRtStudent(python, File("../ml/tests/litert_worker.py"), model).use { student ->
            student.awaitReady()
            expected.clips.forEach { clip ->
                val samples = readPcm16(clips.resolve(clip.file))
                assertEquals(
                    clip.windows.map { it.startSample },
                    AudioPreprocessor.activeWindows(samples).map { it.start },
                    "${clip.file} active window starts",
                )

                val windowProbabilities = mutableListOf<Map<SoundClass, Float>>()
                val classifier = object : AudioClassifier {
                    override fun classify(window: FloatArray): Map<SoundClass, Float> =
                        student.classify(window).also { windowProbabilities.add(it) }
                }
                val probabilities = classifier.classifyClip(samples)
                assertEquals(clip.windows.size, windowProbabilities.size, "${clip.file} model calls")
                clip.windows.zip(windowProbabilities).forEach { (expectedWindow, actual) ->
                    assertProbabilities(
                        expectedWindow.probabilities,
                        actual,
                        "${clip.file} window at sample ${expectedWindow.startSample}",
                    )
                }
                assertProbabilities(clip.probabilities, probabilities, "${clip.file} clip")
                val top = SoundClass.entries.maxBy { probabilities.getValue(it) }
                assertEquals(clip.topClass, top.label, "${clip.file} top class")
            }
        }
    }

    private fun readPcm16(file: File): FloatArray = AudioSystem.getAudioInputStream(file).use { audio ->
        val format = audio.format
        assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.encoding, file.name)
        assertEquals(16, format.sampleSizeInBits, file.name)
        val byteOrder = if (format.isBigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val pcm = ByteBuffer.wrap(audio.readBytes()).order(byteOrder).asShortBuffer()
        val samples = ShortArray(pcm.remaining()) { pcm.get() }
        AudioPreprocessor.pcm16(samples, format.sampleRate.toInt(), format.channels)
    }

    private fun assertProbabilities(expected: List<Float>, actual: Map<SoundClass, Float>, location: String) {
        assertEquals(SoundClass.COUNT, expected.size, location)
        for (soundClass in SoundClass.entries) {
            assertEquals(
                expected[soundClass.index], actual.getValue(soundClass), 0.001f, "$location ${soundClass.label}",
            )
        }
    }
}

@Serializable
private data class ExpectedHandoff(val model: String, val classes: List<String>, val clips: List<ExpectedClip>)

@Serializable
private data class ExpectedClip(
    val file: String,
    val windows: List<ExpectedWindow>,
    @SerialName("clip_probabilities") val probabilities: List<Float>,
    @SerialName("expected_top_class") val topClass: String,
)

@Serializable
private data class ExpectedWindow(@SerialName("start_sample") val startSample: Int, val probabilities: List<Float>)

private class LiteRtStudent(python: String, worker: File, model: File) : AudioClassifier, AutoCloseable {
    private val process = ProcessBuilder(python, worker.path, model.path)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    private val requests = DataOutputStream(process.outputStream.buffered())
    private val responses = DataInputStream(process.inputStream.buffered())

    fun awaitReady() {
        assertEquals(
            0, responses.read(),
            "LiteRT worker failed to start. Check stderr and install ai-edge-litert and numpy for CIUIN_LITERT_PYTHON.",
        )
    }

    override fun classify(window: FloatArray): Map<SoundClass, Float> {
        require(window.size == 15_600) { "LiteRT expected 15600 samples, received ${window.size}" }
        try {
            window.forEach(requests::writeFloat)
            requests.flush()
            return SoundClass.entries.associateWith { responses.readFloat() }
        } catch (error: IOException) {
            throw IllegalStateException("LiteRT worker stopped during inference. Check the test's stderr.", error)
        }
    }

    override fun close() {
        try {
            requests.close()
            check(process.waitFor(5, TimeUnit.SECONDS)) { "LiteRT worker did not exit after closing its input" }
            check(process.exitValue() == 0) { "LiteRT worker exited with status ${process.exitValue()}" }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            responses.close()
        }
    }
}
