package ch.ciuin.shared.audio

import ch.ciuin.shared.model.SoundClass

/** On-device sound classifier. Platform implementations go in androidMain / desktopMain. */
interface AudioClassifier {
    /**
     * @param window mono float samples in [-1, 1] at the model's sample rate.
     * @return a probability for every one of the 9 [SoundClass]es (softmax, sums to 1).
     *   Build it from the model's `[1, 9]` output with `SoundClass.entries[i]`.
     */
    fun classify(window: FloatArray): Map<SoundClass, Float>
}

/** Classifies a prepared 16 kHz mono clip and averages probabilities across active windows. */
fun AudioClassifier.classifyClip(samples: FloatArray): Map<SoundClass, Float> {
    val windows = AudioPreprocessor.activeWindows(samples)
    val sums = DoubleArray(SoundClass.COUNT)
    for (window in windows) {
        val probabilities = classify(window.samples)
        for (soundClass in SoundClass.entries) {
            sums[soundClass.index] += probabilities.getValue(soundClass).toDouble()
        }
    }
    return SoundClass.entries.associateWith { (sums[it.index] / windows.size).toFloat() }
}
