package ch.ciuin.app.listener

/** Decides when to alert using timestamps from a monotonic clock. */
internal class OverwhelmAlertPolicy(
    private val threshold: Double,
    initialSuppressedUntilMillis: Long = Long.MIN_VALUE,
) {
    private var aboveSince: Long? = null
    var aboveLimitMillis: Long? = null
        private set
    var suppressedUntilMillis = initialSuppressedUntilMillis
        private set

    fun onScore(score: Double, nowMillis: Long, alertsEnabled: Boolean = true): Boolean {
        val aboveThreshold = score > threshold
        val crossedThreshold = aboveThreshold && aboveSince == null
        aboveSince = if (aboveThreshold) aboveSince ?: nowMillis else null
        aboveLimitMillis = aboveSince?.let { nowMillis - it }

        if (!alertsEnabled || !crossedThreshold || nowMillis < suppressedUntilMillis) return false

        suppressedUntilMillis = nowMillis + ALERT_INTERVAL_MILLIS
        return true
    }

    fun snooze(nowMillis: Long, durationMillis: Long = SNOOZE_DURATION_MILLIS) {
        suppressedUntilMillis = maxOf(suppressedUntilMillis, nowMillis + durationMillis)
    }

    fun resetCrossing() {
        aboveSince = null
        aboveLimitMillis = null
    }

    private companion object {
        const val ALERT_INTERVAL_MILLIS = 120_000L
        const val SNOOZE_DURATION_MILLIS = 600_000L
    }
}
