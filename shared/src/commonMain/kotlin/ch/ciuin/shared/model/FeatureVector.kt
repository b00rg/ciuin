package ch.ciuin.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shared feature vector (todo.md §0). Used by measurements, the personal model and scoring.
 *
 * `soundClasses` is keyed by class label so the label set can follow `ml/labels.yaml`
 * without a contract change.
 */
@Serializable
data class FeatureVector(
    @SerialName("db_mean") val dbMean: Double,
    @SerialName("db_peak") val dbPeak: Double,
    @SerialName("sound_classes") val soundClasses: Map<String, Double>,
    @SerialName("lux_mean") val luxMean: Double,
    @SerialName("flicker_index") val flickerIndex: Double,
    @SerialName("people_count") val peopleCount: Int,
)
