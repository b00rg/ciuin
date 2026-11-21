package ch.ciuin.app

import androidx.compose.ui.graphics.Color
import ch.ciuin.shared.model.Place

/** Three-step rating, like the TCD Sense Map. LOW is always the "easy" end (calm / dim / quiet / accessible). */
enum class Level { LOW, MEDIUM, HIGH }

// Calm is a dark green: OSM draws parks in a pale green, so a light green band would vanish into the base map.
val LEVEL_LOW = Color(0xFF1B5E20)
val LEVEL_MEDIUM = Color(0xFFF9A825)
val LEVEL_HIGH = Color(0xFFD32F2F)
val LEVEL_UNKNOWN = Color(0xFF9E9E9E)

fun Level?.color(): Color = when (this) {
    Level.LOW -> LEVEL_LOW
    Level.MEDIUM -> LEVEL_MEDIUM
    Level.HIGH -> LEVEL_HIGH
    null -> LEVEL_UNKNOWN
}

/** Heatmap band for a heat value from [Category.heat]: the Low/High thresholds sit at 0.25 / 0.75. */
fun heatBand(t: Double): Level = when {
    t < 0.25 -> Level.LOW
    t > 0.75 -> Level.HIGH
    else -> Level.MEDIUM
}

/**
 * Map layers. Each turns a place into a raw value ([raw]: probability, dB, lux, people, access score),
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
    PERSONAL("Personal", "%", Triple("Lower overwhelm", "Moderate", "Higher overwhelm"), 1.0 / 3.0, 2.0 / 3.0) {
        override fun raw(p: Place, personalScore: Double?): Double? = personalScore

        override fun detail(p: Place): String = when {
            p.measured?.let { it.features != p.personalFeatures } == true -> "From measured and estimated place conditions"
            p.measured != null -> "From measured place conditions"
            p.prior != null -> "From estimated place conditions"
            else -> "No place conditions available"
        }
    },
    SOUND("Sound", "dB", Triple("Quiet", "Moderate", "Loud"), 50.0, 65.0) {
        // Prefer our own measurements; fall back to the sonBASE/EEA baseline.
        override fun raw(p: Place, personalScore: Double?): Double? = p.measured?.features?.dbMean ?: p.baselineNoiseDb

        override fun detail(p: Place): String =
            p.measured?.features?.dbMean?.let { "${it.fmt1()} dB measured" }
                ?: p.baselineNoiseDb?.let { "${it.fmt1()} dB baseline (${p.baselineSource ?: "?"})" }
                ?: "No data"
    },
    LIGHT("Light", "lux", Triple("Dim", "Moderate", "Bright"), 100.0, 500.0) {
        override fun raw(p: Place, personalScore: Double?): Double? = p.measured?.features?.luxMean

        override fun detail(p: Place): String {
            val readings = p.measured?.features ?: return "Not measured yet"
            val lux = readings.luxMean?.let { "${it.toInt()} lux" } ?: "Lux unavailable"
            val flicker = readings.flickerIndex?.let { "flicker ${it.fmt2()}" } ?: "flicker unavailable"
            return "$lux · $flicker"
        }
    },
    CROWD("Crowd", "people", Triple("Quiet", "Busy", "Crowded"), 3.5, 10.5) {
        override fun raw(p: Place, personalScore: Double?): Double? = p.measured?.features?.peopleCount?.toDouble()

        override fun detail(p: Place): String =
            p.measured?.features?.peopleCount?.let { "~$it people" } ?: "Not measured yet"
    },
    ACCESS("Access", "", Triple("Accessible", "Limited", "Not accessible"), 0.5, 1.5) {
        // OSM wheelchair=* tag as 0 (yes) / 1 (limited) / 2 (no), so an area average stays meaningful.
        override fun raw(p: Place, personalScore: Double?): Double? = when (p.wheelchair) {
            "yes", "designated" -> 0.0
            "limited" -> 1.0
            "no" -> 2.0
            else -> null
        }

        override fun detail(p: Place): String = p.wheelchair?.let { "Wheelchair: $it" } ?: "No wheelchair info"
    };

    /** Raw value for a place, or null when we know nothing. */
    abstract fun raw(p: Place, personalScore: Double? = null): Double?

    /** Short human-readable value for the place card. */
    abstract fun detail(p: Place): String

    fun level(p: Place, personalScore: Double? = null): Level? = raw(p, personalScore)?.let { levelOf(it) }

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
