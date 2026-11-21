package ch.ciuin.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import ch.ciuin.shared.model.Place

/** Three-step rating, like the TCD Sense Map. LOW is always the "easy" end (calm / dim / quiet / accessible). */
enum class Level { LOW, MEDIUM, HIGH }

val LEVEL_LOW = Color(0xFF2E7D32)
val LEVEL_MEDIUM = Color(0xFFF9A825)
val LEVEL_HIGH = Color(0xFFC62828)
val LEVEL_UNKNOWN = Color(0xFF9E9E9E)

fun Level?.color(): Color = when (this) {
    Level.LOW -> LEVEL_LOW
    Level.MEDIUM -> LEVEL_MEDIUM
    Level.HIGH -> LEVEL_HIGH
    null -> LEVEL_UNKNOWN
}

/** Continuous green → amber → red for the heatmap; [t] in [0, 1]. */
fun heatColor(t: Double): Color {
    val c = t.coerceIn(0.0, 1.0)
    return if (c < 0.5) lerp(LEVEL_LOW, LEVEL_MEDIUM, (c / 0.5).toFloat())
    else lerp(LEVEL_MEDIUM, LEVEL_HIGH, ((c - 0.5) / 0.5).toFloat())
}

/**
 * Map layers. Each turns a place into a raw value ([raw]: dB, lux, people, access score),
 * which is bucketed into Low / Medium / High by [lowBelow] / [highAbove].
 * Thresholds are first guesses: tune them once we have real measurements (mic is uncalibrated).
 */
enum class Category(
    val title: String,
    val unit: String,
    private val names: Triple<String, String, String>,
    private val lowBelow: Double,
    private val highAbove: Double,
) {
    SOUND("Sound", "dB", Triple("Quiet", "Moderate", "Loud"), 50.0, 65.0) {
        // Prefer our own measurements; fall back to the sonBASE/EEA baseline.
        override fun raw(p: Place): Double? = p.measured?.features?.dbMean ?: p.baselineNoiseDb

        override fun detail(p: Place): String =
            p.measured?.let { "${it.features.dbMean.fmt1()} dB measured" }
                ?: p.baselineNoiseDb?.let { "${it.fmt1()} dB baseline (${p.baselineSource ?: "?"})" }
                ?: "No data"
    },
    LIGHT("Light", "lux", Triple("Dim", "Moderate", "Bright"), 100.0, 500.0) {
        override fun raw(p: Place): Double? = p.measured?.features?.luxMean

        override fun detail(p: Place): String =
            p.measured?.features?.let { "${it.luxMean.toInt()} lux · flicker ${it.flickerIndex.fmt2()}" } ?: "Not measured yet"
    },
    CROWD("Crowd", "people", Triple("Quiet", "Busy", "Crowded"), 3.5, 10.5) {
        override fun raw(p: Place): Double? = p.measured?.features?.peopleCount?.toDouble()

        override fun detail(p: Place): String =
            p.measured?.features?.let { "~${it.peopleCount} people" } ?: "Not measured yet"
    },
    ACCESS("Access", "", Triple("Accessible", "Limited", "Not accessible"), 0.5, 1.5) {
        // OSM wheelchair=* tag as 0 (yes) / 1 (limited) / 2 (no), so an area average stays meaningful.
        override fun raw(p: Place): Double? = when (p.wheelchair) {
            "yes", "designated" -> 0.0
            "limited" -> 1.0
            "no" -> 2.0
            else -> null
        }

        override fun detail(p: Place): String = p.wheelchair?.let { "Wheelchair: $it" } ?: "No wheelchair info"
    };

    /** Raw value for a place, or null when we know nothing. */
    abstract fun raw(p: Place): Double?

    /** Short human-readable value for the place card. */
    abstract fun detail(p: Place): String

    fun level(p: Place): Level? = raw(p)?.let { levelOf(it) }

    fun levelOf(v: Double): Level = when {
        v < lowBelow -> Level.LOW
        v > highAbove -> Level.HIGH
        else -> Level.MEDIUM
    }

    /** Raw value → [0, 1] heat, with the Low/High thresholds landing at 0.25 / 0.75. */
    fun heat(v: Double): Double = (0.25 + 0.5 * (v - lowBelow) / (highAbove - lowBelow)).coerceIn(0.0, 1.0)

    fun levelName(level: Level?): String = when (level) {
        Level.LOW -> names.first
        Level.MEDIUM -> names.second
        Level.HIGH -> names.third
        null -> "No data"
    }
}

internal fun Double.fmt1(): String = ((this * 10).toInt() / 10.0).toString()
internal fun Double.fmt2(): String = ((this * 100).toInt() / 100.0).toString()
