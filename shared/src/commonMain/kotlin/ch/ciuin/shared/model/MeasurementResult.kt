package ch.ciuin.shared.model

import kotlinx.serialization.Serializable

/**
 * Response to POST /measurements (todo.md §0). `place` is the place with its updated aggregates,
 * or null when the place_id isn't a known place (the measurement is still stored and counted).
 */
@Serializable
data class MeasurementResult(
    val stored: Boolean,
    val place: Place? = null,
)
