package ch.ciuin.shared.audio

/** On-device sound classifier. Platform implementations go in androidMain / desktopMain. */
interface AudioClassifier {
    /**
     * @param window mono float samples in [-1, 1] at the model's sample rate.
     * @return class label -> probability.
     */
    fun classify(window: FloatArray): Map<String, Float>
}
