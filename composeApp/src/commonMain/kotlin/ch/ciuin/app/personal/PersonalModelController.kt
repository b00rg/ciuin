package ch.ciuin.app.personal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.ciuin.shared.model.FeatureVector
import ch.ciuin.shared.model.TrainingExample
import ch.ciuin.shared.personal.PersonalPreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

internal class PersonalModelController(
    private val storage: PersonalModelStorage,
    private val scope: CoroutineScope,
) {
    private val operations = Mutex()

    var state by mutableStateOf(PersonalModelUiState())
        private set

    private var latestLiveWindow by mutableStateOf<LiveWindow?>(null)
    val liveScore: Double? get() = latestLiveWindow?.score

    init {
        load()
    }

    fun load() {
        if (state.session != null || state.busy) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            operations.withLock {
                if (state.session == null) {
                    performOperation("Couldn't load your personal settings.") { PersonalModelSession.restore(storage.read()) }
                }
            }
        }
    }

    fun selectPreset(preset: PersonalPreset) {
        if (state.session?.preset == preset) return
        save("Couldn't save the preset.") { it.withPreset(preset) }
    }

    /** Clears the last window when capture stops or becomes unavailable. */
    fun onLiveWindow(features: FeatureVector?): LiveWindow? {
        val window = features?.let { LiveWindow(it, timestamp(), state.session?.score(it)) }
        latestLiveWindow = window
        return window
    }

    fun recordFeedback(placeFeatures: FeatureVector?, label: Int) {
        val live = latestLiveWindow
        val features = live?.features ?: placeFeatures ?: return
        val example = TrainingExample(features, label, live?.timestamp ?: timestamp())
        save(FEEDBACK_FAILED, success = FEEDBACK_SAVED) { it.withFeedback(example) }
    }

    /** Queues feedback for the alert's own window in the app scope. */
    fun recordAlertFeedback(window: LiveWindow, label: Int): Deferred<Boolean> =
        scope.async(start = CoroutineStart.UNDISPATCHED) {
            val example = TrainingExample(window.features, label, window.timestamp)
            saveInOrder(FEEDBACK_FAILED, FEEDBACK_SAVED) { it.withFeedback(example) }
        }

    fun reset() {
        if (state.session?.exampleCount == 0) return
        save("Couldn't clear your feedback.") { it.reset() }
    }

    fun dismissNotice(notice: PersonalModelNotice) {
        if (state.notice === notice) state = state.copy(notice = null)
    }

    private fun save(
        failure: String,
        success: String? = null,
        change: (PersonalModelSession) -> PersonalModelSession,
    ) {
        if (state.session == null || state.busy) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) { saveInOrder(failure, success, change) }
    }

    private suspend fun saveInOrder(
        failure: String,
        success: String?,
        change: (PersonalModelSession) -> PersonalModelSession,
    ): Boolean = operations.withLock {
        val current = state.session ?: return@withLock false
        performOperation(failure, success) {
            val updated = change(current)
            storage.write(updated.encode())
            updated
        }
    }

    private suspend fun performOperation(
        failure: String,
        success: String? = null,
        operation: () -> PersonalModelSession,
    ): Boolean {
        state = state.copy(busy = true, notice = null)
        return try {
            val updated = withContext(Dispatchers.IO) { operation() }
            latestLiveWindow = latestLiveWindow?.let { it.copy(score = updated.score(it.features)) }
            state = PersonalModelUiState(session = updated, notice = success?.let(::PersonalModelNotice))
            true
        } catch (e: CancellationException) {
            state = state.copy(busy = false)
            throw e
        } catch (_: Exception) {
            state = state.copy(busy = false, notice = PersonalModelNotice(failure))
            false
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun timestamp(): String = Clock.System.now().toString()

    private companion object {
        const val FEEDBACK_FAILED = "Couldn't save your feedback."
        const val FEEDBACK_SAVED = "Feedback saved on this device."
    }
}

internal data class LiveWindow(val features: FeatureVector, val timestamp: String, val score: Double?)

internal data class PersonalModelUiState(
    val session: PersonalModelSession? = null,
    val busy: Boolean = false,
    val notice: PersonalModelNotice? = null,
)

internal class PersonalModelNotice(val message: String)
