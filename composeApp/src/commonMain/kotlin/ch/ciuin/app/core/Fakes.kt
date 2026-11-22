package ch.ciuin.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.ciuin.app.measure.AudioSample
import ch.ciuin.app.measure.BleSighting
import ch.ciuin.app.measure.CameraSample
import ch.ciuin.app.measure.CapturePermissions
import ch.ciuin.app.measure.MeasurementCapture
import ch.ciuin.app.measure.MeasurementController
import ch.ciuin.app.personal.LiveWindow
import ch.ciuin.app.personal.PersonalModelController
import ch.ciuin.app.personal.PersonalModelSession
import ch.ciuin.app.personal.PersonalModelStorage
import ch.ciuin.app.route.QuietPlaceRoute
import ch.ciuin.app.route.RoutePoint
import ch.ciuin.app.route.WalkingRoute
import ch.ciuin.app.route.WalkingRouter
import ch.ciuin.app.route.distanceMeters
import ch.ciuin.shared.api.FakePlacesApi
import ch.ciuin.shared.api.PlacesApi
import ch.ciuin.shared.model.FeatureVector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json

internal class MemorySettingsStorage(var json: String? = null) : SettingsStorage {
    override fun read(): String? = json
    override fun write(json: String) {
        this.json = json
    }
}

internal class FakePermissions(initial: Map<AppPermission, PermissionStatus> = emptyMap()) : AppPermissions {
    private val statuses = mutableStateMapOf<AppPermission, PermissionStatus>().apply { putAll(initial) }

    override fun status(permission: AppPermission): PermissionStatus = statuses[permission] ?: PermissionStatus.GRANTED

    override suspend fun request(permission: AppPermission): PermissionStatus {
        if (status(permission) == PermissionStatus.NOT_DETERMINED) setStatus(permission, PermissionStatus.GRANTED)
        return status(permission)
    }

    fun setStatus(permission: AppPermission, status: PermissionStatus) {
        statuses[permission] = status
    }

    override fun refresh() = Unit
}

internal class FakeLocationSource(private val point: RoutePoint? = null) : LocationSource {
    override var current by mutableStateOf(point)
        private set

    override fun start() { current = point }
    override fun stop() { current = null }
}

internal class FakeListeningControl(private val settings: SettingsController) : ListeningControl {
    override val isListening: Boolean get() = settings.listenInBackground
    override var message by mutableStateOf<String?>(null)
    override val liveScore: Double? get() = if (isListening) 0.72 else null
    override val liveDb: Double? get() = if (isListening) 71.0 else null
    override var todayStats by mutableStateOf<ListeningStats?>(ListeningStats(47_000, 13_000))

    override fun setListening(listening: Boolean) {
        settings.listenInBackground = listening
    }
}

internal class FakeMeasurementCapture(
    private val permissions: CapturePermissions = CapturePermissions(true, true, true),
    override val canMeasureLux: Boolean = true,
    override val canScanBluetooth: Boolean = true,
) : MeasurementCapture {
    override val device = "Preview"
    override suspend fun requestPermissions() = permissions
    override fun camera() = emptyFlow<CameraSample>()
    override fun lux() = flowOf(145.0)
    override fun audio() = flowOf(AudioSample(53.0, 59.0, null, "Student S"))
    override fun bluetooth() = flowOf(BleSighting(1, -61), BleSighting(2, -73), BleSighting(1, -60), BleSighting(3, -88))
}

/** Local sample data for offscreen rendering and UI tests. The route controller never reaches the network. */
internal fun previewAppEnvironment(
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    api: PlacesApi = FakePlacesApi(),
    settingsData: AppSettings = AppSettings(
        onboardingComplete = true,
        listenInBackground = true,
        recentPlaces = listOf(RecentPlace("fake:3", "Palais de Rumine"), RecentPlace("fake:1", "Place de la Riponne")),
        sharedMeasurements = 4,
    ),
    showAlert: Boolean = false,
    showRoute: Boolean = false,
): AppEnvironment {
    val origin = RoutePoint(46.5236, 6.6327)
    val destination = RoutePoint(46.5225, 6.6345)
    val route = QuietPlaceRoute(origin, destination, "fake:3", "Palais de Rumine", distanceMeters(origin, destination))
    val settings = SettingsController(MemorySettingsStorage(Json.encodeToString(settingsData)))
    val storage = object : PersonalModelStorage {
        private var saved: String? = null
        override fun read() = saved
        override fun write(json: String) { saved = json }
    }
    val personal = PersonalModelController(storage, scope, PersonalModelSession.restore(null))
    val router = WalkingRouter(baseUrl = "")
    val routes = RouteController(scope) { start, end -> WalkingRoute(listOf(start, end), distanceMeters(start, end)) }
    val alerts = AlertCenter(settings, routes, recordOkay = { alert ->
        personal.recordAlertFeedback(alert.window, label = 0)
    })
    if (showAlert) {
        val window = LiveWindow(FeatureVector(dbMean = 71.0), "2026-11-22T13:00:00Z", 0.72)
        alerts.show(ActiveAlert("preview", 71.0, 42_000, window, QuietPlaceSuggestion(route, 41.0, 45.0, true)))
    }
    if (showRoute) routes.start(route)
    val location = FakeLocationSource(origin)
    val available = setOf(ModelVariant.S, ModelVariant.M)
    return AppEnvironment(
        api, personal, router, settings, FakePermissions(), location,
        FakeListeningControl(settings), alerts, routes,
        MeasurementController(FakeMeasurementCapture(), api, settings, location, scope),
        ModelCatalog(settings, available, parseModelResults(modelResultsJson, available)),
    )
}
