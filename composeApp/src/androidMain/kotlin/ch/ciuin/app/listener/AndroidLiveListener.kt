package ch.ciuin.app.listener

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import ch.ciuin.app.core.ModelVariant
import ch.ciuin.app.measure.AudioSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val AUDIO_SAMPLE_RATE = 16_000

internal class AndroidLiveListener(private val context: Context, private val variant: ModelVariant) {
    suspend fun listen(onWindow: suspend (AudioSample) -> Unit) = withContext(Dispatchers.IO) {
        coroutineScope {
            val source = audioSource()
            // Capture keeps running during inference. Keep just the newest unread window.
            val windows = Channel<FloatArray>(Channel.CONFLATED, onUndeliveredElement = { it.fill(0f) })
            val capture = launch {
                captureWindows(source) { if (windows.trySend(it).isFailure) it.fill(0f) }
            }
            try {
                LiveAudioFeatures(context, source, variant).use { extractor ->
                    for (window in windows) {
                        val features = try {
                            extractor.extract(window)
                        } finally {
                            window.fill(0f)
                        }
                        withContext(Dispatchers.Main.immediate) { onWindow(features) }
                    }
                }
            } finally {
                capture.cancel()
                windows.cancel()
            }
        }
    }

    private fun audioSource(): Int {
        val audioManager = context.getSystemService(AudioManager::class.java)
        return if (audioManager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    private suspend fun captureWindows(source: Int, onSamples: (FloatArray) -> Unit) {
        val channels = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val bufferBytes = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, channels, encoding)
        check(bufferBytes > 0) { "16 kHz mono PCM recording is unavailable" }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission is required")
        }
        val recorder = AudioRecord(
            source,
            AUDIO_SAMPLE_RATE,
            channels,
            encoding,
            maxOf(bufferBytes, AUDIO_SAMPLE_RATE * Short.SIZE_BYTES),
        )
        val samples = ShortArray(AUDIO_SAMPLE_RATE)
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize microphone" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Could not start microphone" }
            var offset = 0
            while (currentCoroutineContext().isActive) {
                val count = recorder.read(samples, offset, samples.size - offset, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "Microphone read failed ($count)" }
                if (count == 0) {
                    delay(20)
                    continue
                }
                offset += count
                if (offset == samples.size) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        check(recorder.activeRecordingConfiguration?.isClientSilenced != true) { "Android has silenced the microphone" }
                    }
                    val window = FloatArray(samples.size) { samples[it] / 32_768f }
                    samples.fill(0)
                    onSamples(window)
                    offset = 0
                }
            }
        } finally {
            samples.fill(0)
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } finally {
                recorder.release()
            }
        }
    }
}
