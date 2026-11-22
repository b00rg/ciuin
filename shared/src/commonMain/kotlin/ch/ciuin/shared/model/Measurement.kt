package ch.ciuin.shared.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phone -> backend (todo.md §0). Aggregates only: never raw audio, images, embeddings,
 * trigger taps or user identity.
 */
@Serializable
data class Measurement(
    @SerialName("place_id") val placeId: String,
    val lat: Double,
    val lon: Double,
    /** ISO-8601 UTC, e.g. "2026-11-21T14:05:00Z". */
    val timestamp: String,
    @SerialName("duration_s") val durationS: Int,
    val features: FeatureVector,
    @SerialName("flicker_hz") val flickerHz: Double? = null,
    @SerialName("model_variant") val modelVariant: String,
    val device: String,
    /** Random ID for this capture, reused when retrying an upload. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("measurement_id") val measurementId: String? = null,
)
