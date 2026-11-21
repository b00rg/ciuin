package ch.ciuin.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shared feature vector (todo.md §0). Used by measurements, the personal model and scoring.
 *
 * Null means the reading was unavailable. When present on the wire, `soundClasses` holds all
 * 9 [SoundClass] labels (e.g. "speech_crowd") -> probability.
 * On the wire it's plain string keys so the backend needs no enum; use [sound] for typed access.
 */
@Serializable
data class FeatureVector(
    @SerialName("db_mean") val dbMean: Double? = null,
    @SerialName("db_peak") val dbPeak: Double? = null,
    @SerialName("sound_classes") val soundClasses: Map<String, Double>? = null,
    @SerialName("lux_mean") val luxMean: Double? = null,
    @SerialName("flicker_index") val flickerIndex: Double? = null,
    /** Legacy / estimates only: phones no longer count people (no camera person detection). */
    @SerialName("people_count") val peopleCount: Int? = null,
    /**
     * Crowd proxy: distinct Bluetooth LE devices heard nearby (above a signal-strength cut-off) during the
     * measurement. Counts devices, not people; shown as busyness levels, never as a head count.
     */
    @SerialName("nearby_devices") val nearbyDevices: Int? = null,
) {
    /** Probability for one class; 0 if absent. */
    fun sound(c: SoundClass): Double = soundClasses?.get(c.label) ?: 0.0
}
