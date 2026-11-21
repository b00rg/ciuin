package ch.ciuin.app

import android.app.Application
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.ciuin.app.listener.OverwhelmAlertPolicy
import ch.ciuin.app.personal.AndroidPersonalModelStorage
import ch.ciuin.app.personal.PersonalModelController
import ch.ciuin.app.personal.PersonalModelSession
import ch.ciuin.app.route.QuietPlaceRoute
import ch.ciuin.app.route.RoutePoint
import ch.ciuin.app.route.WalkingRouter
import ch.ciuin.app.route.decodeOfflinePlaces
import ch.ciuin.app.route.findQuietPlace
import ch.ciuin.shared.api.FallbackPlacesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    internal val walkingRouter by lazy {
        val configuredUrl = BuildConfig.WALKING_ROUTER_URL
        when {
            configuredUrl == null -> WalkingRouter()
            configuredUrl.isBlank() -> null
            else -> WalkingRouter(configuredUrl)
        }
    }
    internal val personalModel by lazy { PersonalModelController(AndroidPersonalModelStorage(this), scope) }
    internal val overwhelmAlerts by lazy {
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

    internal var isListening by mutableStateOf(false)
    internal var listenerMessage by mutableStateOf<String?>(null)

    internal suspend fun quietPlaceRoute(origin: RoutePoint, model: PersonalModelSession): QuietPlaceRoute? =
        withContext(Dispatchers.IO) {
            val nearbyApi = (placesApi as? FallbackPlacesApi)?.primary ?: placesApi
            findQuietPlace(nearbyApi, origin, model::score) { offlinePlaces }
        }

    internal fun saveAlertSuppression() {
        if (bootCount < 0) return
        alertPreferences.edit()
            .putInt("boot_count", bootCount)
            .putLong("suppressed_until", overwhelmAlerts.suppressedUntilMillis)
            .apply()
    }
}
