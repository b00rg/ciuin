package ch.ciuin.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shared feature vector (todo.md §0). Used by measurements, the personal model and scoring.
 *
 * `soundClasses` holds all 9 [SoundClass] labels (e.g. "speech_crowd") -> probability.
 * On the wire it's plain string keys so the backend needs no enum; use [sound] for typed access.
 */
@Serializable
data class FeatureVector(
    @SerialName("db_mean") val dbMean: Double,
    @SerialName("db_peak") val dbPeak: Double,
    @SerialName("sound_classes") val soundClasses: Map<String, Double>,
    @SerialName("lux_mean") val luxMean: Double,
    @SerialName("flicker_index") val flickerIndex: Double,
    @SerialName("people_count") val peopleCount: Int,
) {
    /** Probability for one class; 0 if absent. */
    fun sound(c: SoundClass): Double = soundClasses[c.label] ?: 0.0
}
