package ch.ciuin.app.listener

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import ch.ciuin.app.R
import ch.ciuin.app.core.ActiveAlert
import ch.ciuin.app.core.withAlert
import ch.ciuin.app.route.quietPlaceAlertIntent
import kotlin.math.roundToInt

internal object OverwhelmAlertNotification {
    private const val ID = 2
    private const val CHANNEL_ID = "overwhelm_alerts"

    fun show(context: Context, alert: ActiveAlert): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.overwhelm_alert_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                enableVibration(true)
                setShowBadge(false)
            },
        )
        if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false

        manager.notify(ID, build(context, alert, feedbackSaved = null))
        return true
    }

    fun updateFeedback(context: Context, alert: ActiveAlert, saved: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val notification = build(context, alert, feedbackSaved = saved)
        val alertIsVisible = manager.activeNotifications.any {
            it.id == ID && it.tag == null && it.notification.extras?.getString(LiveListeningService.EXTRA_ALERT_ID) == alert.id
        }
        if (alertIsVisible) manager.notify(ID, notification)
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }

    private fun build(context: Context, alert: ActiveAlert, feedbackSaved: Boolean?): Notification {
        val route = alert.suggestion?.route
        val alertId = alert.id
        // PendingIntent identity ignores extras, so the alert ID also belongs in its URI.
        val alertUri = Uri.Builder().scheme("ciuin").authority("overwhelm-alert").appendPath(alertId).build()
        val openApp = PendingIntent.getActivity(
            context,
            ID,
            context.quietPlaceAlertIntent(route).setData(alertUri).withAlert(alert),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val description = if (route == null) {
            context.getString(R.string.overwhelm_alert_description)
        } else {
            context.getString(R.string.overwhelm_alert_quiet_place, route.destinationName, route.distanceMeters.roundToInt())
        }
        val feedbackStatus = when (feedbackSaved) {
            true -> context.getString(R.string.overwhelm_alert_feedback_saved)
            false -> context.getString(R.string.overwhelm_alert_feedback_failed)
            null -> null
        }
        val expandedText = if (feedbackStatus == null) description else "$feedbackStatus\n$description"
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_overwhelm_alert)
            .setContentTitle(context.getString(R.string.overwhelm_alert_title))
            .setContentText(feedbackStatus ?: description)
            .setStyle(Notification.BigTextStyle().bigText(expandedText))
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setOnlyAlertOnce(feedbackStatus != null)
            .addExtras(Bundle().apply { putString(LiveListeningService.EXTRA_ALERT_ID, alertId) })
            .apply {
                if (feedbackSaved != true) {
                    addAction(action(context, alertUri, alertId, LiveListeningService.ACTION_TOO_MUCH, R.string.overwhelm_alert_too_much))
                    addAction(action(context, alertUri, alertId, LiveListeningService.ACTION_THIS_IS_FINE, R.string.overwhelm_alert_this_is_fine))
                }
                addAction(action(context, alertUri, alertId, LiveListeningService.ACTION_SNOOZE, R.string.overwhelm_alert_snooze))
            }
            .build()
    }

    private fun action(context: Context, alertUri: Uri, alertId: String, action: String, label: Int): Notification.Action {
        val intent = Intent(context, LiveListeningService::class.java)
            .setAction(action)
            .setData(alertUri)
            .putExtra(LiveListeningService.EXTRA_ALERT_ID, alertId)
        val pendingIntent = PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null, context.getString(label), pendingIntent).build()
    }
}
