@file:OptIn(kotlin.time.ExperimentalTime::class)


package ch.ciuin.app.insight

import ch.ciuin.shared.model.Place
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

enum class OpenStatus { OPEN, CLOSED, UNKNOWN }

data class PlaceHours(val status: OpenStatus, val closingTimeToday: String? = null)

private val zurich = TimeZone.of("Europe/Zurich")
private val weekdays = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
private val rule = Regex("""(?:([A-Za-z]{2}(?:\s*[-,]\s*[A-Za-z]{2})*)\s+)?(.+)""")
private val span = Regex("""(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})""")

/**
 * Local evaluation also works offline. Use the backend status for schedules that need richer OSM rules.
 * When open, the closing time ends the current opening. Otherwise it is the last closing time today.
 */
fun placeHours(place: Place, at: Instant = Clock.System.now()): PlaceHours {
    val value = place.osmTags["opening_hours"]?.trim()
    if (value == "24/7") return PlaceHours(OpenStatus.OPEN)
    val week = value?.let(::parseWeek) ?: return PlaceHours(
        when (place.openNow) {
            true -> OpenStatus.OPEN
            false -> OpenStatus.CLOSED
            null -> OpenStatus.UNKNOWN
        },
    )

    val local = at.toLocalDateTime(zurich)
    val today = week[local.dayOfWeek.ordinal].orEmpty()
    val minute = local.hour * 60 + local.minute
    val current = today.firstOrNull { minute in it.start until it.end }
    val end = (current ?: today.lastOrNull())?.end?.takeUnless {
        val tomorrow = week[(local.dayOfWeek.ordinal + 1) % weekdays.size]
        it == 24 * 60 && tomorrow?.firstOrNull()?.start == 0
    }
    return PlaceHours(
        status = if (current == null) OpenStatus.CLOSED else OpenStatus.OPEN,
        closingTimeToday = end?.let { "${(it / 60).toString().padStart(2, '0')}:${(it % 60).toString().padStart(2, '0')}" },
    )
}

private data class Opening(val start: Int, val end: Int)

/** Accept plain weekly selectors and same-day spans. Overlapping rules can have OSM override semantics. */
private fun parseWeek(value: String): Map<Int, List<Opening>>? {
    val week = mutableMapOf<Int, List<Opening>>()
    for (chunk in value.split(';')) {
        val match = rule.matchEntire(chunk.trim()) ?: return null
        val selector = match.groupValues[1]
        val days = if (selector.isEmpty()) weekdays.indices.toSet() else parseDays(selector) ?: return null
        val hours = match.groupValues[2].trim()
        val openings = when (hours) {
            "off", "closed" -> emptyList()
            else -> parseOpenings(hours) ?: return null
        }
        for (day in days) {
            if (week.put(day, openings) != null) return null
        }
    }
    return week
}

private fun parseDays(selector: String): Set<Int>? = buildSet {
    for (part in selector.split(',')) {
        val endpoints = part.split('-').map { weekdays.indexOf(it.trim()) }
        if (endpoints.size !in 1..2 || endpoints.any { it < 0 }) return null
        val first = endpoints.first()
        val last = endpoints.last()
        val length = (last - first + weekdays.size) % weekdays.size
        for (offset in 0..length) add((first + offset) % weekdays.size)
    }
}

private fun parseOpenings(hours: String): List<Opening>? {
    val intervals = hours.split(',').map { part ->
        val match = span.matchEntire(part.trim()) ?: return null
        val (startHour, startMinute, endHour, endMinute) = match.groupValues.drop(1).map(String::toInt)
        if (startHour !in 0..23 || endHour !in 0..24 || startMinute !in 0..59 || endMinute !in 0..59) return null
        val start = startHour * 60 + startMinute
        val end = endHour * 60 + endMinute
        if (end <= start || end > 24 * 60) return null
        Opening(start, end)
    }.sortedBy { it.start }
    return buildList {
        for (interval in intervals) {
            val previous = lastOrNull()
            if (previous != null && interval.start <= previous.end) {
                set(lastIndex, Opening(previous.start, maxOf(previous.end, interval.end)))
            } else add(interval)
        }
    }
}
