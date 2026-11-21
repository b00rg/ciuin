package ch.ciuin.shared.model

import kotlinx.serialization.Serializable

/**
 * Personal trigger model example (todo.md §0). Stays on the phone, never synced.
 * label: 1 = user tapped "Too much"; 0 = "This is fine" or an automatic background sample.
 */
@Serializable
data class TrainingExample(
    val features: FeatureVector,
    val label: Int,
    /** ISO-8601 UTC. */
    val timestamp: String,
)
