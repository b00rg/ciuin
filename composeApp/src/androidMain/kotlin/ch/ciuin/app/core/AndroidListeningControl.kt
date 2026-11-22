package ch.ciuin.app.core

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import ch.ciuin.app.CiuinApplication
import ch.ciuin.app.listener.ListeningNotification
import ch.ciuin.app.listener.LiveListeningService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

internal class AndroidListeningControl(
    private val activity: ComponentActivity,
    private val app: CiuinApplication,
    private val permissions: AndroidPermissions,
    private val scope: CoroutineScope,
) : ListeningControl {
    override val isListening: Boolean get() = app.isListening
    override val message: String? get() = app.listenerMessage
    override val liveScore: Double? get() = app.personalModel.liveScore
    override val liveDb: Double? get() = app.liveDb
    override val todayStats: ListeningStats get() = app.listeningStats.today
    override fun levels(): Flow<Float> = app.audioLevels

    private var starting: Job? = null

    override fun setListening(listening: Boolean) {
        if (!listening) {
            starting?.cancel()
            LiveListeningService.stop(activity)
            app.settings.listenInBackground = false
            return
        }
        if (isListening || starting?.isActive == true || !isForeground()) return
        starting = scope.launch {
            try {
                app.settings.awaitLoaded()
                if (!isForeground()) return@launch
                if (permissions.request(AppPermission.MICROPHONE) != PermissionStatus.GRANTED) {
                    app.listenerMessage = "Allow microphone access to start listening."
                    return@launch
                }
                if (permissions.request(AppPermission.NOTIFICATIONS) != PermissionStatus.GRANTED ||
                    !ListeningNotification.canShow(activity)
                ) {
                    app.listenerMessage = "Enable listening notifications in Android settings to start listening."
                    return@launch
                }
                if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    permissions.requestPreciseLocation()
                }
                if (!isForeground()) return@launch
                app.listenerMessage = null
                LiveListeningService.start(activity)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                app.listenerMessage = "Couldn't start listening. Try again from the map."
            }
        }
    }

    private fun isForeground() = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
