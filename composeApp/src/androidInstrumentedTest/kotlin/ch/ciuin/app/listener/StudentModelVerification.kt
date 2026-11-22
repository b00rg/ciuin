package ch.ciuin.app.listener

import android.app.Activity
import android.app.Instrumentation
import android.media.MediaRecorder
import android.os.Bundle
import ch.ciuin.app.core.ModelVariant
import ch.ciuin.shared.model.SoundClass
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs

class StudentModelVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            result.putString("studentModels", verifyModels())
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("failure", failure.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun verifyModels(): String {
        val reference = context.assets.open("student-model-reference.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        val hashes = reference.getJSONObject("modelSha256")
        val clips = reference.getJSONArray("clips")
        return listOf(ModelVariant.S, ModelVariant.M).joinToString("\n") { variant ->
            val asset = "audio-student-${variant.name.lowercase()}.tflite"
            val bytes = targetContext.assets.open(asset).use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(hash == hashes.getString(variant.name)) { "Wrong bundled export: $asset" }

            var inferred = 0
            var quiet = 0
            var maxError = 0.0
            // This is the same extractor used by both the foreground measurement and background listener.
            LiveAudioFeatures(targetContext, MediaRecorder.AudioSource.VOICE_RECOGNITION, variant).use { extractor ->
                for (index in 0 until clips.length()) {
                    val fixture = clips.getJSONObject(index)
                    val name = fixture.getString("file")
                    val expected = fixture.getJSONArray(variant.name)
                    val sample = extractor.extract(readWindow(name))
                    check(sample.modelVariant == "Student ${variant.name}") { "Wrong reported model for $name" }
                    val probabilities = checkNotNull(sample.soundClasses)
                    if (expected.length() == 0) {
                        check(probabilities.isEmpty()) { "$name should skip inference below the silence threshold" }
                        quiet++
                        continue
                    }
                    check(probabilities.keys == SoundClass.entries.map { it.label }.toSet()) { "Wrong labels for $name" }
                    for (label in SoundClass.entries) {
                        val difference = abs(probabilities.getValue(label.label) - expected.getDouble(label.index))
                        check(difference <= PROBABILITY_TOLERANCE) { "$asset / $name / ${label.label}: error $difference" }
                        maxError = maxOf(maxError, difference)
                    }
                    inferred++
                }
            }
            "Student ${variant.name}: $inferred inference windows, $quiet quiet window(s), max error $maxError, SHA-256 $hash"
        }
    }

    private fun readWindow(name: String): FloatArray {
        val bytes = context.assets.open(name).use { it.readBytes() }
        val wav = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(bytes.size >= WAV_HEADER_SIZE && bytes.sliceArray(36..39).decodeToString() == "data") {
            "Expected a PCM WAV with a 44-byte header: $name"
        }
        check(
            wav.getShort(20).toInt() == 1 && wav.getShort(22).toInt() == 1 &&
                wav.getInt(24) == AUDIO_SAMPLE_RATE && wav.getShort(34).toInt() == 16,
        ) { "Expected 16 kHz mono PCM16 WAV: $name" }
        val frames = minOf(AUDIO_SAMPLE_RATE, wav.getInt(40) / Short.SIZE_BYTES)
        wav.position(WAV_HEADER_SIZE)
        return FloatArray(AUDIO_SAMPLE_RATE).also { window ->
            repeat(frames) { frame -> window[frame] = wav.short / 32_768f }
        }
    }

    private companion object {
        const val WAV_HEADER_SIZE = 44
        const val PROBABILITY_TOLERANCE = 0.001
    }
}
