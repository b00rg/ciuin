package ch.ciuin.shared.audio

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.function.DoubleSupplier
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val path = args.singleOrNull()
    if (path.isNullOrBlank()) {
        System.err.println("Usage: ./gradlew -q :shared:replayStage0Wav '-Pwav=path/to/recording.wav'")
        exitProcess(2)
    }

    val file = File(path)
    try {
        require(AudioSystem.getAudioFileFormat(file).type == AudioFileFormat.Type.WAVE) {
            "expected a WAV file"
        }
        AudioSystem.getAudioInputStream(file).use(::replay)
    } catch (error: UnsupportedAudioFileException) {
        failReplay(file, "expected a readable PCM WAV file")
    } catch (error: IOException) {
        failReplay(file, error.message ?: "could not read the WAV file")
    } catch (error: IllegalArgumentException) {
        failReplay(file, error.message ?: "invalid WAV format or data")
    }
}

private fun replay(input: AudioInputStream) {
    val format = input.format
    val sampleRateHz = format.sampleRate.toInt()
    require(sampleRateHz > 0 && sampleRateHz.toFloat() == format.sampleRate) {
        "expected a positive whole-number sample rate"
    }
    require(format.channels > 0 && format.frameSize > 0 &&
        format.frameSize == format.channels * (format.sampleSizeInBits / 8)) {
        "expected packed PCM frames with at least one channel"
    }
    require(!format.isBigEndian) { "expected little-endian PCM samples" }
    require(format.frameSize <= Int.MAX_VALUE / sampleRateHz) { "PCM window is too large" }

    val analyzer = Stage0AudioAnalyzer(sampleRateHz)
    val bytes = ByteArray(sampleRateHz * format.frameSize)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val readSample = buffer.pcmSampleReader(format)
    val window = FloatArray(sampleRateHz)

    println("start_s,level_dbfs_a,spectral_flux")
    var startSeconds = 0L
    while (true) {
        val bytesRead = input.readNBytes(bytes, 0, bytes.size)
        if (bytesRead < bytes.size) {
            if (startSeconds == 0L) {
                System.err.println("The WAV contains no complete one-second windows.")
            } else if (bytesRead > 0) {
                System.err.println("Skipped ${bytesRead / format.frameSize} trailing frames after $startSeconds s.")
            }
            return
        }

        buffer.rewind()
        for (frame in window.indices) {
            var sum = 0.0
            repeat(format.channels) {
                val sample = readSample.asDouble
                require(sample.isFinite()) { "non-finite PCM sample in the window starting at $startSeconds s" }
                sum += sample.coerceIn(-1.0, 1.0)
            }
            window[frame] = (sum / format.channels).toFloat()
        }
        val metrics = analyzer.analyze(window)
        println("$startSeconds,${metrics.levelDbfsA},${metrics.spectralFlux}")
        startSeconds++
    }
}

private fun ByteBuffer.pcmSampleReader(format: AudioFormat): DoubleSupplier =
    when (format.encoding to format.sampleSizeInBits) {
        AudioFormat.Encoding.PCM_UNSIGNED to 8 -> DoubleSupplier { ((get().toInt() and 0xff) - 128) / 128.0 }
        AudioFormat.Encoding.PCM_SIGNED to 16 -> DoubleSupplier { short / 32_768.0 }
        AudioFormat.Encoding.PCM_SIGNED to 24 -> DoubleSupplier {
            val low = get().toInt() and 0xff
            val middle = get().toInt() and 0xff
            val high = get().toInt()
            ((high shl 16) or (middle shl 8) or low) / 8_388_608.0
        }
        AudioFormat.Encoding.PCM_SIGNED to 32 -> DoubleSupplier { int / 2_147_483_648.0 }
        AudioFormat.Encoding.PCM_FLOAT to 32 -> DoubleSupplier { float.toDouble() }
        AudioFormat.Encoding.PCM_FLOAT to 64 -> DoubleSupplier { double }
        else -> throw IllegalArgumentException("unsupported WAV encoding: ${format.encoding} ${format.sampleSizeInBits}-bit")
    }

private fun failReplay(file: File, reason: String): Nothing {
    System.err.println("${file.path}: $reason")
    exitProcess(1)
}
