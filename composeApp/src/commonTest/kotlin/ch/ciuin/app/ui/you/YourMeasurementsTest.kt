@file:OptIn(kotlin.time.ExperimentalTime::class)

package ch.ciuin.app.ui.you

import ch.ciuin.app.core.MeasurementRecord
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class YourMeasurementsTest {
    private val zurich = TimeZone.of("Europe/Zurich")
    private val now = Instant.parse("2026-11-22T15:00:00Z")

    @Test
    fun saysTodayYesterdayOrTheDateInLocalTime() {
        assertEquals("Today, 14:20", measuredWhen("2026-11-22T13:20:00Z", now, zurich))
        assertEquals("Yesterday, 09:05", measuredWhen("2026-11-21T08:05:00Z", now, zurich))
        assertEquals("3 Oct 2026, 18:30", measuredWhen("2026-10-03T16:30:00Z", now, zurich))
        // Just after midnight in Zurich is still "today" there, though it is the day before in UTC.
        assertEquals("Today, 00:10", measuredWhen("2026-11-21T23:10:00Z", now, zurich))
        assertEquals("not a time", measuredWhen("not a time", now, zurich))
    }

    @Test
    fun listsTheReadingsThatWereSent() {
        fun record(db: Double? = null, lux: Double? = null, devices: Int? = null, hz: Double? = null) =
            MeasurementRecord(null, null, 46.5, 6.6, "2026-11-22T10:00:00Z", db, lux, devices, hz)
        assertEquals("57 dB · 82 lux · 3 devices", readingsLine(record(57.4, 82.0, 3)))
        assertEquals("1 device", readingsLine(record(devices = 1)))
        assertEquals("100 Hz flicker", readingsLine(record(hz = 100.0)))
        assertEquals("Sound categories only", readingsLine(record()))
    }
}
