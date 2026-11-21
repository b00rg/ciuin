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
    /** Outdoor road+rail noise around the place, daytime 06-22, dB(A). */
    @SerialName("baseline_noise_db") val baselineNoiseDb: Double? = null,
    /** Same, night 22-06. */
    @SerialName("baseline_noise_night_db") val baselineNoiseNightDb: Double? = null,
    /** "sonbase", "eea" or null outside regions with noise data. */
    @SerialName("baseline_source") val baselineSource: String? = null,
    /**
     * Selected raw OSM tags (opening_hours, outdoor_seating, indoor_seating, capacity, cuisine, brand,
     * smoking, air_conditioning, live_music, level, toilets:wheelchair, website, ...). Absent keys = unknown.
     */
    @SerialName("osm_tags") val osmTags: Map<String, String> = emptyMap(),
    /**
     * Open right now (or at the `at` time the request asked for), from osm_tags["opening_hours"].
     * null = unknown: no tag, or a format the backend doesn't evaluate (e.g. "by appointment").
     * Computed by the backend; null in the bundled offline file.
     */
    @SerialName("open_now") val openNow: Boolean? = null,
    /**
     * ESTIMATED feature vector (category + outdoor noise + capacity), so the personal model can score
     * places nobody has measured. Never present it as measured data: use [measured] when it exists,
     * otherwise fall back to this (or blend, trusting [measured] more as its n grows).
     */
    val prior: FeatureVector? = null,
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
