package ch.ciuin.app.listener

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import ch.ciuin.app.MainActivity
import ch.ciuin.app.R

internal object ListeningNotification {
    const val ID = 1
    private const val CHANNEL_ID = "live_listening"

    fun canShow(context: Context): Boolean {
        val manager = createChannel(context) ?: return false
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return false
        return manager.areNotificationsEnabled() && channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun create(service: Context): Notification {
        checkNotNull(createChannel(service)) { "Notification service unavailable" }
        val openApp = PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopListening = PendingIntent.getService(
            service,
            0,
            Intent(service, LiveListeningService::class.java).setAction(LiveListeningService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_live_microphone)
            .setContentTitle(service.getString(R.string.live_listening_title))
            .setContentText(service.getString(R.string.live_listening_description))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp)
            .setDeleteIntent(stopListening)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(service, R.drawable.ic_stop_listening),
                    service.getString(R.string.live_listening_stop),
                    stopListening,
                ).build(),
            )
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    private fun createChannel(context: Context): NotificationManager? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.live_listening_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
        return manager
    }
}
