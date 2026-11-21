package ch.ciuin.app.listener

/** Decides when to alert using timestamps from a monotonic clock. */
internal class OverwhelmAlertPolicy(
    private val threshold: Double,
    initialSuppressedUntilMillis: Long = Long.MIN_VALUE,
) {
    private var wasAboveThreshold = false
    var suppressedUntilMillis = initialSuppressedUntilMillis
        private set

    fun onScore(score: Double, nowMillis: Long): Boolean {
        val aboveThreshold = score > threshold
        val crossedThreshold = aboveThreshold && !wasAboveThreshold
        wasAboveThreshold = aboveThreshold

        if (!crossedThreshold || nowMillis < suppressedUntilMillis) return false

        suppressedUntilMillis = nowMillis + ALERT_INTERVAL_MILLIS
        return true
    }

    fun snooze(nowMillis: Long) {
        suppressedUntilMillis = maxOf(suppressedUntilMillis, nowMillis + SNOOZE_DURATION_MILLIS)
    }

    fun resetCrossing() {
        wasAboveThreshold = false
    }

    private companion object {
        const val ALERT_INTERVAL_MILLIS = 120_000L
        const val SNOOZE_DURATION_MILLIS = 600_000L
    }
}
