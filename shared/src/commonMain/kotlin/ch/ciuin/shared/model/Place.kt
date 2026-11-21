package ch.ciuin.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Backend -> app (todo.md §0). */
@Serializable
data class Place(
    @SerialName("place_id") val placeId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val category: String,
    /** OSM wheelchair tag value ("yes", "limited", "no") or null. */
    val wheelchair: String? = null,
    /** OSM sensory_friendly tag value, or null when untagged. */
    @SerialName("sensory_friendly") val sensoryFriendly: String? = null,
    @SerialName("quiet_room") val quietRoom: Boolean = false,
    @SerialName("baseline_noise_db") val baselineNoiseDb: Double? = null,
    /** "sonbase", "eea" or null outside regions with noise data. */
    @SerialName("baseline_source") val baselineSource: String? = null,
    val measured: MeasuredAggregate? = null,
    val score: PlaceScore? = null,
)

/** Mean feature vector over `n` measurements, so the personal model can re-score on-device. */
@Serializable
data class MeasuredAggregate(
    val n: Int,
    val features: FeatureVector,
)

/** Generic per-dimension scores in [0, 1] for users without a personal model. */
@Serializable
data class PlaceScore(
    val noise: Double,
    val light: Double,
    val flicker: Double,
    val crowd: Double,
    val access: Double,
)
