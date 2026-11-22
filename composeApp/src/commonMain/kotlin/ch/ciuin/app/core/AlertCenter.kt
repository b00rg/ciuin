package ch.ciuin.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.ciuin.app.personal.LiveWindow
import ch.ciuin.app.route.QuietPlaceRoute

internal data class QuietPlaceSuggestion(
    val route: QuietPlaceRoute,
    val measuredDb: Double? = null,
    val priorDb: Double? = null,
    val openNow: Boolean? = null,
) {
    val name: String get() = route.destinationName
    val distanceMeters: Double get() = route.distanceMeters
}

internal data class ActiveAlert(
    val id: String,
    val currentDb: Double?,
    val aboveLimitMillis: Long,
    val window: LiveWindow,
    val suggestion: QuietPlaceSuggestion? = null,
    val hapticOnDisplay: Boolean = false,
)

/** Keeps the scored window tied to the alert even as live listening continues. */
internal class AlertCenter(
    private val settings: SettingsController,
    private val routes: RouteController,
    private val recordOkay: (ActiveAlert) -> Unit,
    private val onSnooze: (Int) -> Unit = {},
    private val onDismiss: (ActiveAlert) -> Unit = {},
) {
    var active by mutableStateOf<ActiveAlert?>(null)
        private set

    fun show(alert: ActiveAlert) {
        if (settings.gentleAlerts) active = alert
    }

    /** Updates the duration while the same threshold crossing continues. */
    fun updateDuration(id: String, aboveLimitMillis: Long) {
        val alert = active?.takeIf { it.id == id } ?: return
        active = alert.copy(aboveLimitMillis = aboveLimitMillis)
    }

    fun takeMeThere() {
        val suggestion = active?.suggestion ?: return
        routes.start(suggestion.route)
        dismiss()
    }

    fun okayHere() {
        val alert = active ?: return
        recordOkay(alert)
        dismiss()
    }

    fun snooze(minutes: Int = 15) {
        if (active == null) return
        onSnooze(minutes.coerceAtLeast(1))
        dismiss()
    }

    fun dismiss() {
        val alert = active ?: return
        active = null
        onDismiss(alert)
    }
}
