package ch.ciuin.app

import ch.ciuin.app.ui.theme.systemReduceMotion
import ch.ciuin.app.core.Appearance
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import ch.ciuin.app.core.PermissionStatus
import ch.ciuin.app.core.AppPermission
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import ch.ciuin.app.core.AppEnvironment
import ch.ciuin.app.map.MapMarker
import ch.ciuin.app.map.MapState
import ch.ciuin.app.map.MapView
import ch.ciuin.app.map.projectQuietPlaceRoute
import ch.ciuin.app.map.rememberMapTileCache
import ch.ciuin.app.measure.MeasurementPhase
import ch.ciuin.app.ui.alert.AlertScreen
import ch.ciuin.app.ui.components.AttributionPlate
import ch.ciuin.app.ui.components.CiuinTab
import ch.ciuin.app.ui.components.CiuinTabBar
import ch.ciuin.app.ui.components.Level
import ch.ciuin.app.ui.globe.GlobeOpening
import ch.ciuin.app.ui.measure.MeasureTab
import ch.ciuin.app.ui.onboarding.OnboardingFlow
import ch.ciuin.app.ui.search.SearchScreen
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinTheme
import ch.ciuin.app.ui.you.YouTab
import ch.ciuin.shared.model.Place

private enum class PlaceSearchTarget { Map, Measure }

/** How long the map waits at launch for a first position fix before keeping the default view. */
private const val START_LOCATION_TIMEOUT_MS = 5_000L

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun App(environment: AppEnvironment) {
    val settings = environment.settings
    val personal = environment.personalModel
    val routes = environment.routes
    val snackbar = remember { SnackbarHostState() }
    val map = remember { MapState(LAUSANNE_LAT, LAUSANNE_LON, LAUSANNE_ZOOM) }
    val mapTiles = rememberMapTileCache()
    var tab by rememberSaveable { mutableStateOf(CiuinTab.Map) }
    var showGlobe by rememberSaveable { mutableStateOf(true) }
    var searchTarget by rememberSaveable { mutableStateOf<PlaceSearchTarget?>(null) }
    var selectedPlaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var measurementPlaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var measurementChoiceId by rememberSaveable { mutableStateOf<String?>(null) }
    val measurementPhase = environment.measurement?.phase
    val keepMeasurementVisible = tab == CiuinTab.Measure && when (measurementPhase) {
        MeasurementPhase.Requesting,
        is MeasurementPhase.Capturing,
        is MeasurementPhase.Review,
        is MeasurementPhase.Uploading,
        is MeasurementPhase.Done -> true
        else -> false
    }
    var mapCategory by rememberSaveable { mutableStateOf(Category.SOUND) }
    val data = rememberMapData(environment.api, mapCategory)
    val places = data.places
    val session = personal.state.session
    val scores = remember(places, session) {
        if (session == null) emptyMap()
        else places.mapNotNull { place ->
            place.personalFeatures?.let { place.placeId to session.score(it) }
        }.toMap()
    }

    DisposableEffect(environment.location) {
        environment.location.start()
        onDispose { environment.location.stop() }
    }
    // Open the map where the user is. Only when location access was already granted (opening the app never
    // asks), only once, and never after the user has moved the map themselves. Otherwise it stays on the
    // default view (the area with the most data).
    LaunchedEffect(map) {
        if (environment.permissions.status(AppPermission.LOCATION) != PermissionStatus.GRANTED) return@LaunchedEffect
        val startX = map.centerX
        val startY = map.centerY
        val here = withTimeoutOrNull(START_LOCATION_TIMEOUT_MS) {
            snapshotFlow { environment.location.current }.filterNotNull().first()
        } ?: return@LaunchedEffect
        if (map.centerX == startX && map.centerY == startY) map.centerOn(here.lat, here.lon, LAUSANNE_ZOOM)
    }
    LaunchedEffect(personal.state.notice) {
        val notice = personal.state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice.message)
        personal.dismissNotice(notice)
    }
    LaunchedEffect(routes.requestCount, keepMeasurementVisible) {
        if (!keepMeasurementVisible && routes.active != null) {
            selectedPlaceId = null
            searchTarget = null
            showGlobe = false
            tab = CiuinTab.Map
        }
    }
    LaunchedEffect(measurementPhase) {
        if (measurementPhase is MeasurementPhase.Done && measurementPhase.stored) data.retry()
    }

    fun switchTab(next: CiuinTab) {
        if (next == tab) return
        measurementPlaceId = null
        measurementChoiceId = null
        selectedPlaceId = null
        searchTarget = null
        tab = next
    }

    val alert = environment.alerts.active
    val route = routes.active
    val bottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val tabBar: @Composable () -> Unit = {
        CiuinTabBar(tab, ::switchTab, bottomInset = bottomInset)
    }
    fun dismissMapOverlay() {
        when {
            searchTarget != null -> searchTarget = null
            selectedPlaceId != null -> selectedPlaceId = null
            routes.active != null -> routes.end()
        }
    }
    fun pickFromSearch(place: Place) {
        when (searchTarget) {
            PlaceSearchTarget.Map -> {
                map.centerOn(place.lat, place.lon, PLACE_ZOOM)
                selectedPlaceId = place.placeId
            }
            PlaceSearchTarget.Measure -> {
                measurementPlaceId = place.placeId
                measurementChoiceId = place.placeId
            }
            null -> return
        }
        searchTarget = null
    }

    val dark = settings.appearance == Appearance.Dark
    // Motion follows the phone's own setting; the app no longer has its own switch.
    CiuinTheme(reduceMotion = systemReduceMotion(), dark = dark) {
        Box(Modifier.fillMaxSize().background(CiuinColors.Ground)) {
            when {
                settings.loading -> Unit
                alert != null && !keepMeasurementVisible -> {
                    AlertScreen(environment, alert, mapPreview = {
                        val destination = alert.suggestion?.route ?: return@AlertScreen
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val preview = remember(destination) {
                                MapState(destination.destination.lat, destination.destination.lon, 15.0)
                            }
                            val overlay = remember(destination) { projectQuietPlaceRoute(destination, null) }
                            val density = LocalDensity.current
                            val view = with(density) { Size(maxWidth.toPx() * 0.76f, maxHeight.toPx() * 0.66f) }
                            val tilePx = with(density) { 256.dp.toPx() }
                            LaunchedEffect(overlay, view, tilePx) {
                                if (!view.isEmpty()) preview.fitRoute(overlay, view, tilePx)
                            }
                            val markers = remember(destination) {
                                listOf(MapMarker(destination.destinationId, destination.destination.lat,
                                    destination.destination.lon, destination.destinationName, Level.Calm))
                            }
                            MapView(
                                preview, markers, destination.destinationId, {}, Modifier.fillMaxSize(),
                                routeOverlay = overlay, interactive = false, tileCache = mapTiles,
                            )
                            AttributionPlate(
                                "© OpenStreetMap contributors",
                                Modifier.align(Alignment.BottomStart).padding(4.dp),
                            )
                        }
                    })
                }
                !settings.onboardingComplete && route == null -> OnboardingFlow(environment, onFinished = { showGlobe = true })
                showGlobe && route == null -> GlobeOpening(environment, onEnterMap = { here ->
                    showGlobe = false
                    if (here != null) map.centerOn(here.lat, here.lon, LAUSANNE_ZOOM)
                })
                searchTarget != null -> {
                    BackHandler(onBack = ::dismissMapOverlay)
                    val searchPlaces = if (searchTarget == PlaceSearchTarget.Measure && data.usingSamplePlaces) emptyList() else places
                    SearchScreen(environment, searchPlaces, scores, ::pickFromSearch, ::dismissMapOverlay)
                }
                tab == CiuinTab.Measure -> {
                    BackHandler(enabled = measurementPhase !is MeasurementPhase.Uploading) { switchTab(CiuinTab.Map) }
                    MeasureTab(
                        environment = environment,
                        places = places.takeUnless { data.usingSamplePlaces }.orEmpty(),
                        preselected = places.takeUnless { data.usingSamplePlaces }
                            ?.firstOrNull { it.placeId == measurementPlaceId },
                        onBackToMap = { switchTab(CiuinTab.Map) },
                        choiceId = measurementChoiceId,
                        onChoiceChanged = { measurementChoiceId = it },
                        onResetPlace = {
                            measurementPlaceId = null
                            measurementChoiceId = null
                        },
                        onFindPlace = if (places.isNotEmpty() && !data.usingSamplePlaces) {
                            { searchTarget = PlaceSearchTarget.Measure }
                        } else null,
                        tabBar = tabBar,
                        onPlaceUpdated = data::updatePlace,
                    )
                }
                tab == CiuinTab.You -> {
                    BackHandler { switchTab(CiuinTab.Map) }
                    YouTab(
                        environment,
                        tabBar,
                        places = places,
                        onOpenPlace = { place ->
                            switchTab(CiuinTab.Map)
                            map.centerOn(place.lat, place.lon, PLACE_ZOOM)
                            selectedPlaceId = place.placeId
                        },
                        onOpenSpot = { lat, lon ->
                            switchTab(CiuinTab.Map)
                            map.centerOn(lat, lon, PLACE_ZOOM)
                        },
                    )
                }
                else -> {
                    BackHandler(enabled = selectedPlaceId != null || route != null, onBack = ::dismissMapOverlay)
                    MapScreen(
                        environment = environment,
                        data = data,
                        state = map,
                        tiles = mapTiles,
                        category = mapCategory,
                        onCategory = { mapCategory = it },
                        personalScores = scores,
                        selected = places.firstOrNull { it.placeId == selectedPlaceId },
                        onSelectPlace = { selectedPlaceId = it?.placeId },
                        onSearch = { searchTarget = PlaceSearchTarget.Map },
                        onMeasure = environment.measurement?.let {
                            { place ->
                                switchTab(CiuinTab.Measure)
                                measurementPlaceId = place?.placeId
                                measurementChoiceId = place?.placeId
                            }
                        },
                        showMessage = { snackbar.showSnackbar(it) },
                        tabBar = tabBar,
                    )
                }
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = if (tab == CiuinTab.Map && route == null && alert == null &&
                        selectedPlaceId == null && searchTarget == null) bottomInset + 64.dp else bottomInset),
            )
        }
    }
}
