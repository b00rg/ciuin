package ch.ciuin.app.listener

/**
 * Averages the sound-class probabilities of the last [windows] live windows (one per second).
 *
 * A single 1 s window is noisy: on the test split, student S's per-window macro-F1 goes from
 * 0.552 (one window) to 0.577 averaged over 3 (siren F1 0.640 -> 0.681). See `live_smoothing`
 * in cascade.json. An empty map (a silent window, no model run) passes through and clears the
 * history, so a new sound is not blended with one that has ended.
 */
internal class SoundClassSmoother(private val windows: Int = DEFAULT_WINDOWS) {
    init {
        require(windows >= 1) { "windows must be at least 1" }
    }

    private val history = ArrayDeque<Map<String, Double>>()

    fun smooth(soundClasses: Map<String, Double>?): Map<String, Double>? {
        if (soundClasses.isNullOrEmpty()) {
            history.clear()
            return soundClasses
        }
        history.addLast(soundClasses)
        while (history.size > windows) history.removeFirst()
        return soundClasses.keys.associateWith { label ->
            history.sumOf { it[label] ?: 0.0 } / history.size
        }
    }

    fun reset() = history.clear()

    companion object {
        const val DEFAULT_WINDOWS = 3
    }
}
