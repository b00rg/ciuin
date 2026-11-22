package ch.ciuin.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import ch.ciuin.app.listener.OverwhelmAlertPolicy
import ch.ciuin.app.listener.SoundClassSmoother
import ch.ciuin.app.measure.IosAudioCapture
import ch.ciuin.app.personal.PersonalModelController
import ch.ciuin.shared.model.FeatureVector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUUID
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState.UIApplicationStateBackground
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

@OptIn(ExperimentalTime::class)
internal class IosListeningControl(
    private val audio: IosAudioCapture,
    private val settings: SettingsController,
    private val permissions: AppPermissions,
    private val personal: PersonalModelController,
    routes: RouteController,
    private val scope: CoroutineScope,
) : ListeningControl {
    private var session: Job? = null
    private val startTime = TimeSource.Monotonic.markNow()
    private val policy by lazy { OverwhelmAlertPolicy(audio.overwhelmThreshold) }
    private val stats = IosListeningStats()

    override var isListening by mutableStateOf(false)
        private set
    override var message by mutableStateOf<String?>(null)
        private set
    override val liveScore get() = personal.liveScore
    override val liveDb: Double? get() = null
    override val todayStats get() = stats.today
    override fun levels() = audio.levels

    val alerts = AlertCenter(
        settings, routes, scope,
        recordOkay = { alert -> personal.recordAlertFeedback(alert.window, label = 0) },
        onSnooze = { minutes -> policy.snooze(elapsedMillis(), minutes * 60_000L) },
    )

    init {
        settings.listenInBackground = false
        scope.launch {
            snapshotFlow { settings.gentleAlerts }.collect { enabled ->
                if (!enabled) alerts.dismiss()
            }
        }
    }

    override fun setListening(listening: Boolean) {
        if (!listening) {
            session?.cancel()
            return
        }
        if (session?.isCompleted == false || !isForeground()) return
        session = scope.launch {
            try {
                settings.awaitLoaded()
                if (!isForeground()) return@launch
                if (permissions.request(AppPermission.MICROPHONE) != PermissionStatus.GRANTED) {
                    message = "Allow microphone access to start listening."
                    return@launch
                }
                if (!isForeground()) return@launch
                message = null
                isListening = true
                settings.listenInBackground = true
                collectWindows()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = "Live audio is unavailable. Try starting listening again."
            } finally {
                personal.onLiveWindow(null)
                isListening = false
                settings.listenInBackground = false
                alerts.dismiss()
            }
        }
    }

    fun refreshStats() = stats.refresh()

    private suspend fun collectWindows() {
        val smoother = SoundClassSmoother(audio.liveSmoothingWindows)
        policy.resetCrossing()
        var crossingAlertId: String? = null
        audio.windows.collect { result ->
            val sample = result.getOrThrow()
            val rawClasses = checkNotNull(sample.soundClasses) { "The on-device audio model is unavailable" }
            val classes = smoother.smooth(rawClasses)
            stats.record(ranModel = !classes.isNullOrEmpty())
            // The iOS meter reports dBFS(A). The personal model's level inputs require SPL.
            if (classes.isNullOrEmpty()) {
                personal.onLiveWindow(null)
                policy.resetCrossing()
                crossingAlertId = null
                return@collect
            }
            val window = personal.onLiveWindow(FeatureVector(soundClasses = classes)) ?: return@collect
            val score = window.score ?: return@collect
            if (policy.onScore(score, elapsedMillis(), settings.gentleAlerts)) {
                val id = NSUUID().UUIDString
                crossingAlertId = id
                alerts.show(ActiveAlert(id, null, 0L, window, hapticOnDisplay = true))
            } else {
                val duration = policy.aboveLimitMillis
                if (duration == null) crossingAlertId = null
                else crossingAlertId?.let { alerts.updateDuration(it, duration) }
            }
        }
    }

    private fun elapsedMillis() = startTime.elapsedNow().inWholeMilliseconds

    private fun isForeground() = UIApplication.sharedApplication.applicationState != UIApplicationStateBackground
}

@OptIn(ExperimentalTime::class)
private class IosListeningStats {
    private val defaults = NSUserDefaults.standardUserDefaults
    var today by mutableStateOf(read(today()))
        private set

    fun refresh() {
        today = read(today())
    }

    fun record(ranModel: Boolean) {
        val end = Clock.System.now()
        val zone = TimeZone.currentSystemDefault()
        var start = end - 1.seconds
        while (start < end) {
            val day = start.toLocalDateTime(zone).date
            val next = minOf(end, day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone))
            val key = key(day, ranModel)
            defaults.setInteger(defaults.integerForKey(key) + (next - start).inWholeMilliseconds, forKey = key)
            start = next
        }
        today = read(end.toLocalDateTime(zone).date)
    }

    private fun today() = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    private fun read(day: LocalDate) = ListeningStats(
        asleepMillis = defaults.integerForKey(key(day, ranModel = false)),
        smallModelMillis = defaults.integerForKey(key(day, ranModel = true)),
    )

    private fun key(day: LocalDate, ranModel: Boolean) =
        "ch.ciuin.listening-time.$day.${if (ranModel) "model" else "asleep"}"
}
