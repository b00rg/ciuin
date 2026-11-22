package ch.ciuin.app.listener

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import ch.ciuin.app.CiuinApplication
import ch.ciuin.app.core.ActiveAlert
import ch.ciuin.app.personal.LiveWindow
import ch.ciuin.app.personal.PersonalModelSession
import ch.ciuin.shared.model.FeatureVector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class LiveListeningService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as CiuinApplication
    private var promoted = false
    private var locationPromoted = false
    private var pendingAlert: Job? = null
    private var displayedAlert: DisplayedAlert? = null
    private var trackedAlertId: String? = null
    private var aboveLimitMillis = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!promoted) startListening()
            ACTION_STOP -> stopSelf()
            ACTION_SNOOZE, ACTION_TOO_MUCH, ACTION_THIS_IS_FINE -> handleAlertAction(intent, startId)
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun handleAlertAction(intent: Intent?, startId: Int) {
        if (activeService !== this) {
            stopSelf(startId)
            return
        }
        val alert = displayedAlert ?: return
        if (intent?.getStringExtra(EXTRA_ALERT_ID) != alert.value.id) return

        when (intent.action) {
            ACTION_TOO_MUCH -> saveAlertFeedback(alert, label = 1)
            ACTION_THIS_IS_FINE -> saveAlertFeedback(alert, label = 0)
            ACTION_SNOOZE -> {
                pendingAlert?.cancel()
                displayedAlert = null
                if (app.alerts.active?.id == alert.value.id) app.alerts.dismiss()
                OverwhelmAlertNotification.dismiss(this)
                scope.launch {
                    val policy = app.overwhelmAlerts.await()
                    policy.snooze(SystemClock.elapsedRealtime())
                    app.saveAlertSuppression(policy)
                }
            }
        }
    }

    private fun saveAlertFeedback(alert: DisplayedAlert, label: Int) {
        if (alert.feedback != FeedbackState.AwaitingChoice) return
        alert.feedback = FeedbackState.Saving
        val result = app.personalModel.recordAlertFeedback(alert.value.window, label)
        scope.launch {
            val saved = result.await()
            if (displayedAlert !== alert || activeService !== this@LiveListeningService) return@launch

            alert.feedback = if (saved) FeedbackState.Saved else FeedbackState.AwaitingChoice
            try {
                OverwhelmAlertNotification.updateFeedback(this@LiveListeningService, alert.value, saved)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not update overwhelm feedback notification", error)
            }
        }
    }

    private fun startListening() {
        try {
            val notification = ListeningNotification.create(this)
            locationPromoted = promoteListener(notification)
            promoted = true
            if (!ListeningNotification.canShow(this)) {
                app.listenerMessage = "Allow notifications to keep Stop listening available."
                stopSelf()
                return
            }

            activeService = this
            app.listenerMessage = null
            app.isListening = true
            app.settings.listenInBackground = true

            scope.launch {
                try {
                    captureWindows()
                    stopSelf()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    onFailure(error)
                } catch (error: LinkageError) {
                    onFailure(error)
                }
            }
        } catch (error: Exception) {
            onFailure(error)
        }
    }

    private fun promoteListener(notification: Notification): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val micType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            if (AlertLocation.canLocate(this)) {
                try {
                    startForeground(ListeningNotification.ID, notification, micType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                    return true
                } catch (error: SecurityException) {
                    Log.w(TAG, "Could not enable location for live alerts", error)
                }
            }
            startForeground(ListeningNotification.ID, notification, micType)
        } else {
            startForeground(ListeningNotification.ID, notification)
            return AlertLocation.canLocate(this)
        }
        return false
    }

    private suspend fun captureWindows() {
        val policy = app.overwhelmAlerts.await()
        policy.resetCrossing()
        val wakeLock = acquireWakeLock()
        try {
            app.audioWindows.collect { result ->
                if (activeService !== this) return@collect
                val audio = result.getOrThrow()
                app.liveDb = audio.dbMean
                // The audio stream consists of one-second windows. Empty classes mean the gate stayed closed.
                app.listeningStats.record(1_000, ranModel = !audio.soundClasses.isNullOrEmpty())
                val window = FeatureVector(audio.dbMean, audio.dbPeak, audio.soundClasses)
                val model = app.personalModel
                val liveWindow = model.onLiveWindow(window) ?: return@collect
                val score = liveWindow.score ?: return@collect
                val shouldAlert = policy.onScore(score, SystemClock.elapsedRealtime(), app.settings.gentleAlerts)
                val duration = policy.aboveLimitMillis
                if (duration == null || duration == 0L) {
                    trackedAlertId = null
                } else {
                    trackedAlertId?.let {
                        aboveLimitMillis = duration
                        app.alerts.updateDuration(it, duration)
                    }
                }
                if (shouldAlert) {
                    postAlert(liveWindow, model.state.session, policy)
                }
            }
        } finally {
            wakeLock.release()
        }
    }

    private fun postAlert(window: LiveWindow, model: PersonalModelSession?, policy: OverwhelmAlertPolicy) {
        pendingAlert?.cancel()
        val id = UUID.randomUUID().toString()
        trackedAlertId = id
        aboveLimitMillis = 0L
        pendingAlert = scope.launch {
            try {
                app.saveAlertSuppression(policy)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not save the alert cooldown", error)
            }
            val suggestion = if (locationPromoted && model != null) {
                try {
                    withTimeoutOrNull(4_000) {
                        val origin = AlertLocation.currentPoint(this@LiveListeningService) ?: return@withTimeoutOrNull null
                        app.quietPlaceSuggestion(origin, model)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Could not find a quiet place for the alert", error)
                    null
                }
            } else {
                null
            }
            if (activeService === this@LiveListeningService && app.settings.gentleAlerts) {
                val alert = ActiveAlert(id, window.features.dbMean, aboveLimitMillis, window, suggestion)
                displayedAlert = DisplayedAlert(alert)
                val notified = try {
                    OverwhelmAlertNotification.show(this@LiveListeningService, alert)
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Could not show overwhelm alert", error)
                    false
                }
                app.alerts.show(alert.copy(hapticOnDisplay = !notified))
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock(): PowerManager.WakeLock {
        val power = checkNotNull(getSystemService(PowerManager::class.java))
        return power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:LiveListening").apply {
            acquire()
        }
    }

    private fun onFailure(error: Throwable) {
        Log.w(TAG, "Live listening failed", error)
        if (activeService == null || activeService === this) {
            app.listenerMessage = "Live audio is unavailable. Check microphone access and try again."
        }
        stopSelf()
    }

    override fun onDestroy() {
        if (activeService === this) {
            activeService = null
            app.personalModel.onLiveWindow(null)
            app.liveDb = null
            app.isListening = false
            val alertId = displayedAlert?.value?.id
            if (alertId != null && app.alerts.active?.id == alertId) app.alerts.dismiss()
            displayedAlert = null
            OverwhelmAlertNotification.dismiss(this)
        }
        if (activeService == null) app.settings.listenInBackground = false
        scope.cancel()
        if (promoted && activeService == null) stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private class DisplayedAlert(val value: ActiveAlert) {
        var feedback = FeedbackState.AwaitingChoice
    }

    private enum class FeedbackState { AwaitingChoice, Saving, Saved }

    companion object {
        private const val TAG = "LiveListeningService"
        private const val ACTION_START = "ch.ciuin.app.listener.START"
        internal const val ACTION_STOP = "ch.ciuin.app.listener.STOP"
        internal const val ACTION_SNOOZE = "ch.ciuin.app.listener.SNOOZE"
        internal const val ACTION_TOO_MUCH = "ch.ciuin.app.listener.TOO_MUCH"
        internal const val ACTION_THIS_IS_FINE = "ch.ciuin.app.listener.THIS_IS_FINE"
        internal const val EXTRA_ALERT_ID = "ch.ciuin.app.listener.extra.ALERT_ID"

        // Service callbacks and window publication use the application main thread.
        private var activeService: LiveListeningService? = null

        internal fun cancelPendingAlert() {
            activeService?.pendingAlert?.cancel()
        }

        internal fun recordInAppFeedback(alert: ActiveAlert): Boolean {
            val service = activeService ?: return false
            val displayed = service.displayedAlert?.takeIf { it.value.id == alert.id } ?: return false
            service.saveAlertFeedback(displayed, label = 0)
            return true
        }

        internal fun start(context: Context) {
            context.startForegroundService(Intent(context, LiveListeningService::class.java).setAction(ACTION_START))
        }

        internal fun stop(context: Context) {
            context.stopService(Intent(context, LiveListeningService::class.java))
        }
    }
}
