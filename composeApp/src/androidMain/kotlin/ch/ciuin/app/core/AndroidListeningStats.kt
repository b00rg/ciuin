package ch.ciuin.app.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Only elapsed time per stage is stored. Split windows that straddle local midnight. */
internal class AndroidListeningStats(context: Context) {
    private val preferences = context.getSharedPreferences("listening_time", Context.MODE_PRIVATE)
    var today by mutableStateOf(ListeningStats())
        private set

    init { refresh() }

    fun refresh() {
        today = read(LocalDate.now())
    }

    fun record(windowMillis: Long, ranModel: Boolean) {
        if (windowMillis <= 0) return
        val end = Instant.now()
        val zone = ZoneId.systemDefault()
        var start = end.minusMillis(windowMillis)
        while (start < end) {
            val date = start.atZone(zone).toLocalDate()
            val boundary = date.plusDays(1).atStartOfDay(zone).toInstant()
            val next = minOf(end, boundary)
            val key = key(date, ranModel)
            val duration = next.toEpochMilli() - start.toEpochMilli()
            preferences.edit().putLong(key, preferences.getLong(key, 0) + duration).apply()
            start = next
        }
        refresh()
    }

    private fun read(date: LocalDate) = ListeningStats(
        asleepMillis = preferences.getLong(key(date, false), 0),
        smallModelMillis = preferences.getLong(key(date, true), 0),
    )

    private fun key(date: LocalDate, ranModel: Boolean) = "$date-${if (ranModel) "model" else "asleep"}"
}
