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

/**
 * One measurement the user shared, kept on this phone so the You tab can list it. Only the numbers
 * that were sent are kept; [placeId] is null for "Somewhere else", whose [lat] and [lon] are already
 * coarsened to about 100 m before sending.
 */
@Serializable
internal data class MeasurementRecord(
    val placeId: String?,
    val placeName: String?,
    val lat: Double,
    val lon: Double,
    /** ISO-8601 UTC, as sent. */
    val timestamp: String,
    val dbMean: Double? = null,
    val luxMean: Double? = null,
    val nearbyDevices: Int? = null,
    val flickerHz: Double? = null,
)

/** Light or dark palette, chosen on the You tab. An unknown saved value reads as [Light]. */
@Serializable
internal enum class Appearance { Light, Dark }

@Serializable
internal data class AppSettings(
    val onboardingComplete: Boolean = false,
    val listenInBackground: Boolean = false,
    val gentleAlerts: Boolean = true,
    val reduceMotion: Boolean? = null,
    val recentPlaces: List<RecentPlace> = emptyList(),
    val sharedMeasurements: Int = 0,
    /** Newest first, at most [MEASUREMENT_HISTORY_LIMIT]. */
    val measurementHistory: List<MeasurementRecord> = emptyList(),
    val modelVariant: ModelVariant = ModelVariant.S,
    val appearance: Appearance = Appearance.Light,
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

    var appearance: Appearance
        get() = state.appearance
        set(value) = update { copy(appearance = value) }

    val recentPlaces: List<RecentPlace> get() = state.recentPlaces
    val sharedMeasurements: Int get() = state.sharedMeasurements
    val measurementHistory: List<MeasurementRecord> get() = state.measurementHistory
    val modelVariant: ModelVariant get() = state.modelVariant

    fun refreshSystemReduceMotion(value: Boolean) {
        systemMotion = value
    }

    fun rememberPlace(placeId: String, name: String) {
        if (placeId.isBlank() || name.isBlank()) return
        update { copy(recentPlaces = (listOf(RecentPlace(placeId, name)) + recentPlaces).normalized()) }
    }

    /** Counts a shared measurement (a duck) and, when given, keeps its [record] for the You tab. */
    fun recordSharedMeasurement(record: MeasurementRecord? = null) = update {
        copy(
            sharedMeasurements = sharedMeasurements.inc(),
            measurementHistory = if (record == null) measurementHistory
            else (listOf(record) + measurementHistory).take(MEASUREMENT_HISTORY_LIMIT),
        )
    }

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

/** How many shared measurements the phone keeps for the You tab; older ones drop off the end. */
internal const val MEASUREMENT_HISTORY_LIMIT = 200
