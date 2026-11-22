package ch.ciuin.app.core

import android.content.Intent
import android.os.Bundle
import ch.ciuin.app.personal.LiveWindow
import ch.ciuin.app.route.isQuietPlaceAlert
import ch.ciuin.app.route.quietPlaceRoute
import ch.ciuin.shared.api.ContractJson
import ch.ciuin.shared.model.FeatureVector

private const val ALERT_EXTRA = "ch.ciuin.app.extra.ACTIVE_ALERT"

/** The PendingIntent carries the aggregate window so an alert can be restored after process death. */
internal fun Intent.withAlert(alert: ActiveAlert): Intent = putExtra(ALERT_EXTRA, Bundle().apply {
    putLong("duration", alert.aboveLimitMillis)
    alert.currentDb?.let { putDouble("db", it) }
    putString("features", ContractJson.encodeToString(alert.window.features))
    putString("timestamp", alert.window.timestamp)
    alert.window.score?.let { putDouble("score", it) }
    alert.suggestion?.let { suggestion ->
        suggestion.measuredDb?.let { putDouble("measured", it) }
        suggestion.priorDb?.let { putDouble("prior", it) }
        suggestion.openNow?.let { putBoolean("open", it) }
    }
})

internal fun Intent.activeAlert(): ActiveAlert? {
    if (!isQuietPlaceAlert() || data?.scheme != "ciuin" || data?.authority != "overwhelm-alert") return null
    val id = data?.lastPathSegment ?: return null
    return runCatching {
        val extras = getBundleExtra(ALERT_EXTRA) ?: return null
        val window = LiveWindow(
            ContractJson.decodeFromString<FeatureVector>(extras.getString("features") ?: return null),
            extras.getString("timestamp") ?: return null,
            extras.optionalDouble("score"),
        )
        val suggestion = quietPlaceRoute()?.let { route ->
            QuietPlaceSuggestion(
                route, extras.optionalDouble("measured"), extras.optionalDouble("prior"),
                if (extras.containsKey("open")) extras.getBoolean("open") else null,
            )
        }
        ActiveAlert(id, extras.optionalDouble("db"), extras.getLong("duration"), window, suggestion)
    }.getOrNull()
}

private fun Bundle.optionalDouble(key: String) = getDouble(key, Double.NaN).takeIf { it.isFinite() }
