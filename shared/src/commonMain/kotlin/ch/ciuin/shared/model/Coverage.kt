package ch.ciuin.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GET /coverage (todo.md §0): coarse global grid for the 3D globe. Never per-place. */
@Serializable
data class Coverage(
    @SerialName("res_deg") val resDeg: Double,
    val cells: List<CoverageCell>,
)

/** One grid cell; `lat`/`lon` are the cell centre. Empty cells are omitted. */
@Serializable
data class CoverageCell(
    val lat: Double,
    val lon: Double,
    val wheelchair: Int = 0,
    @SerialName("sensory_friendly") val sensoryFriendly: Int = 0,
    /** "sonbase", "eea" or null when no baseline noise data covers this cell. */
    @SerialName("noise_source") val noiseSource: String? = null,
    val measurements: Int = 0,
)
