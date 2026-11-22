package ch.ciuin.app

import android.app.Application
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import ch.ciuin.app.core.AlertCenter
import ch.ciuin.app.core.AndroidListeningStats
import ch.ciuin.app.core.AndroidSettingsStorage
import ch.ciuin.app.core.ModelCatalog
import ch.ciuin.app.core.ModelVariant
import ch.ciuin.app.core.QuietPlaceSuggestion
import ch.ciuin.app.core.RouteController
import ch.ciuin.app.core.SettingsController
import ch.ciuin.app.core.androidReduceMotion
import ch.ciuin.app.listener.AndroidLiveListener
import ch.ciuin.app.listener.LiveListeningService
import ch.ciuin.app.listener.OverwhelmAlertNotification
import ch.ciuin.app.listener.OverwhelmAlertPolicy
import ch.ciuin.app.measure.AudioSample
import ch.ciuin.app.personal.AndroidPersonalModelStorage
import ch.ciuin.app.personal.PersonalModelController
import ch.ciuin.app.personal.PersonalModelSession
import ch.ciuin.app.route.RoutePoint
import ch.ciuin.app.route.WalkingRouter
import ch.ciuin.app.route.decodeOfflinePlaces
import ch.ciuin.app.route.findQuietPlace
import ch.ciuin.shared.api.FallbackPlacesApi
import ch.ciuin.shared.api.PlacesApi
import ch.ciuin.shared.model.Place
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CiuinApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val alertPreferences by lazy { getSharedPreferences("overwhelm_alerts", MODE_PRIVATE) }
    private val bootCount by lazy { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1) }
    private val offlinePlaces by lazy {
        assets.open("places_lausanne.geojson").bufferedReader().use { decodeOfflinePlaces(it.readText()) }
    }

    internal val placesApi by lazy { createPlacesApi() }
    internal val settings by lazy { SettingsController(AndroidSettingsStorage(this), androidReduceMotion(this), scope) }
    internal val models by lazy {
        ModelCatalog(settings, setOf(ModelVariant.S, ModelVariant.M), runningVariant = { activeAudioVariant })
            .apply { load(scope) }
    }
    internal val listeningStats by lazy { AndroidListeningStats(this) }
    private var activeAudioVariant by mutableStateOf<ModelVariant?>(null)

    // Both capture flows subscribe here so a foreground measurement can reuse the listener's microphone.
    internal val audioWindows by lazy {
        callbackFlow<Result<AudioSample>> {
            settings.awaitLoaded()
            val variant = settings.modelVariant.takeIf { it == ModelVariant.M } ?: ModelVariant.S
            activeAudioVariant = variant
            try {
                AndroidLiveListener(this@CiuinApplication, variant).listen { send(Result.success(it)) }
            } finally {
                activeAudioVariant = null
            }
            close()
        }.catch { emit(Result.failure(it)) }.buffer(0).shareIn(scope, SharingStarted.WhileSubscribed())
    }

    internal val walkingRouter by lazy {
        val configuredUrl = BuildConfig.WALKING_ROUTER_URL
        when {
            configuredUrl == null -> WalkingRouter()
            configuredUrl.isBlank() -> null
            else -> WalkingRouter(configuredUrl)
        }
    }
    internal val routes by lazy { RouteController(scope, walkingRouter) }
    internal val personalModel by lazy { PersonalModelController(AndroidPersonalModelStorage(this), scope) }
    internal val alerts by lazy {
        AlertCenter(
            settings, routes,
            recordOkay = { alert ->
                if (!LiveListeningService.recordInAppFeedback(alert)) {
                    personalModel.recordAlertFeedback(alert.window, label = 0)
                }
            },
            onSnooze = { minutes ->
                LiveListeningService.cancelPendingAlert()
                scope.launch {
                    val policy = overwhelmAlerts.await()
                    policy.snooze(SystemClock.elapsedRealtime(), minutes * 60_000L)
                    saveAlertSuppression(policy)
                }
            },
            onDismiss = { OverwhelmAlertNotification.dismiss(this) },
        )
    }

    internal val overwhelmAlerts by lazy {
        scope.async(Dispatchers.IO) {
            val config = assets.open("cascade.json").bufferedReader().use { Json.parseToJsonElement(it.readText()) }
            val threshold = config.jsonObject.getValue("stage1_to_2").jsonObject
                .getValue("p_overwhelm_threshold").jsonPrimitive.double
            // Elapsed-realtime deadlines can be reused across app restarts within the same boot.
            val suppressedUntil = if (bootCount >= 0 && alertPreferences.getInt("boot_count", -1) == bootCount) {
                alertPreferences.getLong("suppressed_until", Long.MIN_VALUE)
            } else {
                Long.MIN_VALUE
            }
            OverwhelmAlertPolicy(threshold, suppressedUntil)
        }
    }

    internal var isListening by mutableStateOf(false)
    internal var listenerMessage by mutableStateOf<String?>(null)
    internal var liveDb by mutableStateOf<Double?>(null)

    override fun onCreate() {
        super.onCreate()
        // The listening service is explicitly started in the foreground and is not sticky.
        settings.listenInBackground = false
        scope.launch {
            snapshotFlow { settings.gentleAlerts }.collect { enabled ->
                if (!enabled) {
                    LiveListeningService.cancelPendingAlert()
                    alerts.dismiss()
                    OverwhelmAlertNotification.dismiss(this@CiuinApplication)
                }
            }
        }
    }

    internal suspend fun quietPlaceSuggestion(origin: RoutePoint, model: PersonalModelSession): QuietPlaceSuggestion? =
        withContext(Dispatchers.IO) {
            val nearbyApi = (placesApi as? FallbackPlacesApi)?.primary ?: placesApi
            var candidates = emptyList<Place>()
            // Retain the data returned for scoring so the alert can show provenance without a second lookup.
            val collectingApi = object : PlacesApi by nearbyApi {
                override suspend fun nearby(lat: Double, lon: Double, radiusM: Int, openOnly: Boolean, at: String?) =
                    nearbyApi.nearby(lat, lon, radiusM, openOnly, at).also { candidates = it }
            }
            val route = findQuietPlace(collectingApi, origin, model::score) {
                offlinePlaces.also { candidates = it }
            } ?: return@withContext null
            val place = candidates.firstOrNull { it.placeId == route.destinationId }
            QuietPlaceSuggestion(
                route,
                measuredDb = place?.measured?.features?.dbMean,
                priorDb = place?.prior?.dbMean ?: place?.baselineNoiseDb,
                openNow = place?.openNow,
            )
        }

    internal fun saveAlertSuppression(policy: OverwhelmAlertPolicy) {
        if (bootCount < 0) return
        alertPreferences.edit()
            .putInt("boot_count", bootCount)
            .putLong("suppressed_until", policy.suppressedUntilMillis)
            .apply()
    }
}
