package ch.ciuin.app.ui.place

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import ch.ciuin.app.Category
import ch.ciuin.app.insight.DistanceUnit
import ch.ciuin.app.insight.OpenStatus
import ch.ciuin.app.insight.PlaceFact
import ch.ciuin.app.insight.PlaceHours
import ch.ciuin.app.insight.distanceTokens
import ch.ciuin.app.ui.components.Level
import ch.ciuin.app.ui.toUi
import kotlin.math.roundToInt

/*
 * The words the place sheet, search, alert and route panels build from data. They live together so the
 * four pieces say the same thing the same way ("80 m", "open now", "Too much").
 */

/** "80 m", "1.2 km". Null for a distance that is not a finite, non-negative number. */
internal fun distanceText(metres: Double?): String? {
    if (metres == null || !metres.isFinite() || metres < 0) return null
    val tokens = distanceTokens(metres)
    return when (tokens.unit) {
        DistanceUnit.METRES -> "${tokens.value} m"
        DistanceUnit.KILOMETRES -> "${tokens.value} km"
    }
}

/** "56 dB". */
internal fun dbText(db: Double): String = "${db.roundToInt()} dB"

/**
 * "open until 19:00", "open now" or "closed"; null when the hours are unknown. [withClosingTime] false
 * gives the short form the search rows use.
 */
internal fun hoursText(hours: PlaceHours, withClosingTime: Boolean = true): String? = when (hours.status) {
    OpenStatus.OPEN -> hours.closingTimeToday?.takeIf { withClosingTime }?.let { "open until $it" } ?: "open now"
    OpenStatus.CLOSED -> "closed"
    OpenStatus.UNKNOWN -> null
}

/** The level the personal model gives a predicted overwhelm score, in the design's words. */
internal fun personalLevel(score: Double?): Level = score?.let { Category.PERSONAL.levelOf(it) }.toUi()

/** "Calm", "Moderate", "Too much", "No data": the For you word of a place in search. */
internal val Level.forYouWord: String
    get() = when (this) {
        Level.Calm -> "Calm"
        Level.Moderate -> "Moderate"
        Level.Loud -> "Too much"
        Level.NoData -> "No data"
    }

/** A fact chip's label, or null for the facts the sheet shows elsewhere (wheelchair access has its own row). */
internal val PlaceFact.label: String?
    get() = when (this) {
        PlaceFact.QUIET_ROOM -> "Quiet room"
        PlaceFact.SENSORY_FRIENDLY -> "Sensory friendly"
        PlaceFact.OUTDOOR_SEATING -> "Outdoor seating"
        PlaceFact.INDOOR_SEATING -> "Indoor seating"
        PlaceFact.LIVE_MUSIC -> "Live music"
        PlaceFact.NO_LIVE_MUSIC -> "No live music"
        PlaceFact.AIR_CONDITIONING -> "Air conditioning"
        PlaceFact.ACCESSIBLE_TOILET -> "Accessible toilet"
        PlaceFact.NO_SMOKING -> "No smoking"
        PlaceFact.WHEELCHAIR_ACCESSIBLE, PlaceFact.WHEELCHAIR_LIMITED, PlaceFact.WHEELCHAIR_NOT_ACCESSIBLE -> null
    }

/** "two minutes", "a minute", "12 minutes": walking times in a sentence. */
internal fun minutesInWords(minutes: Int): String = when (minutes) {
    1 -> "a minute"
    in 2..9 -> "${NUMBER_WORDS[minutes]} minutes"
    else -> "$minutes minutes"
}

/** "20 seconds", "a minute", "3 minutes": how long something has lasted, in a sentence. */
internal fun durationInWords(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(1)
    return when {
        seconds == 1L -> "a second"
        seconds < 60 -> "$seconds seconds"
        else -> minutesInWords((seconds / 60).toInt())
    }
}

private val NUMBER_WORDS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")

/**
 * Lets a sheet grow with its content up to [fraction] of the height its parent offers (the screen, when
 * the map hosts it in a full-screen box), and no taller. Content that should scroll past that inside
 * the sheet needs its own `verticalScroll`.
 */
internal fun Modifier.maxHeightFraction(fraction: Float): Modifier = layout { measurable, constraints ->
    val limited = if (constraints.hasBoundedHeight) {
        constraints.copy(maxHeight = (constraints.maxHeight * fraction).toInt().coerceAtLeast(constraints.minHeight))
    } else {
        constraints
    }
    val placeable = measurable.measure(limited)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}
