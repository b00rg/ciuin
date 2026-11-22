package ch.ciuin.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal interface SettingsStorage {
    fun read(): String?
    fun write(json: String)
}

@Serializable
internal data class RecentPlace(val placeId: String, val name: String)

@Serializable
internal data class AppSettings(
    val onboardingComplete: Boolean = false,
    val listenInBackground: Boolean = false,
    val gentleAlerts: Boolean = true,
    val reduceMotion: Boolean? = null,
    val recentPlaces: List<RecentPlace> = emptyList(),
    val sharedMeasurements: Int = 0,
    val modelVariant: ModelVariant = ModelVariant.S,
)

/** A null motion override tracks the native setting, including changes made outside the app. */
internal class SettingsController(
    private val storage: SettingsStorage,
    systemReduceMotion: Boolean = false,
    scope: CoroutineScope? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private val loaded = CompletableDeferred<Unit>()
    private val earlyEdits = mutableListOf<AppSettings.() -> AppSettings>()
    private var systemMotion by mutableStateOf(systemReduceMotion)
    var state by mutableStateOf(if (scope == null) restore() else AppSettings())
        private set
    var loading by mutableStateOf(scope != null)
        private set

    init {
        if (scope == null) {
            loaded.complete(Unit)
        } else {
            scope.launch {
                val saved = withContext(Dispatchers.IO) { restore() }
                val merged = earlyEdits.fold(saved) { current, edit -> current.edit() }
                earlyEdits.clear()
                if (merged != saved) storage.write(json.encodeToString(merged))
                state = merged
                loading = false
                loaded.complete(Unit)
            }
        }
    }

    suspend fun awaitLoaded() = loaded.await()

    var onboardingComplete: Boolean
        get() = state.onboardingComplete
        set(value) = update { copy(onboardingComplete = value) }

    var listenInBackground: Boolean
        get() = state.listenInBackground
        set(value) = update { copy(listenInBackground = value) }

    var gentleAlerts: Boolean
        get() = state.gentleAlerts
        set(value) = update { copy(gentleAlerts = value) }

    var reduceMotion: Boolean
        get() = state.reduceMotion ?: systemMotion
        set(value) = update { copy(reduceMotion = value) }

    val recentPlaces: List<RecentPlace> get() = state.recentPlaces
    val sharedMeasurements: Int get() = state.sharedMeasurements
    val modelVariant: ModelVariant get() = state.modelVariant

    fun refreshSystemReduceMotion(value: Boolean) {
        systemMotion = value
    }

    fun rememberPlace(placeId: String, name: String) {
        if (placeId.isBlank() || name.isBlank()) return
        update { copy(recentPlaces = (listOf(RecentPlace(placeId, name)) + recentPlaces).normalized()) }
    }

    fun recordSharedMeasurement() = update { copy(sharedMeasurements = sharedMeasurements.inc()) }

    fun selectModel(variant: ModelVariant) = update { copy(modelVariant = variant) }

    private fun restore(): AppSettings = runCatching {
        val saved = storage.read()?.let { json.decodeFromString<AppSettings>(it) } ?: AppSettings()
        saved.copy(recentPlaces = saved.recentPlaces.normalized(), sharedMeasurements = saved.sharedMeasurements.coerceAtLeast(0))
    }.getOrDefault(AppSettings())

    private fun update(change: AppSettings.() -> AppSettings) {
        val next = state.change()
        if (loading) {
            // Replay actions made while disk settings load so unrelated saved fields survive.
            earlyEdits += change
        } else {
            if (next == state) return
            storage.write(json.encodeToString(next))
        }
        state = next
    }

    private fun List<RecentPlace>.normalized() = distinctBy { it.placeId }.take(5)
}
