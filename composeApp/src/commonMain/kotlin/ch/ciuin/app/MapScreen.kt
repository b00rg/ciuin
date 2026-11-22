package ch.ciuin.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import ch.ciuin.app.core.AppEnvironment
import ch.ciuin.app.core.AppPermission
import ch.ciuin.app.core.PermissionStatus
import ch.ciuin.app.heat.Averaging
import ch.ciuin.app.heat.HeatSample
import ch.ciuin.app.heat.ViewBounds
import ch.ciuin.app.heat.aggregateHex
import ch.ciuin.app.heat.cellLevel
import ch.ciuin.app.heat.hexRadius
import ch.ciuin.app.heat.samplesFor
import ch.ciuin.app.insight.areaInsight
import ch.ciuin.app.map.HeatHex
import ch.ciuin.app.map.MapMarker
import ch.ciuin.app.map.MapState
import ch.ciuin.app.map.MapView
import ch.ciuin.app.map.TileCache
import ch.ciuin.app.map.latToY
import ch.ciuin.app.map.lonToX
import ch.ciuin.app.map.projectQuietPlaceRoute
import ch.ciuin.app.route.nearestCalmSpot
import ch.ciuin.app.route.routeToPlace
import ch.ciuin.app.ui.characters.Sheep
import ch.ciuin.app.ui.characters.SheepPose
import ch.ciuin.app.ui.components.AttributionPlate
import ch.ciuin.app.ui.components.BottomSheetSurface
import ch.ciuin.app.ui.components.CharacterTile
import ch.ciuin.app.ui.components.Level as UiLevel
import ch.ciuin.app.ui.components.LevelGlyph
import ch.ciuin.app.ui.components.ListeningButton
import ch.ciuin.app.ui.components.LayerChipRow
import ch.ciuin.app.ui.components.MapVeil
import ch.ciuin.app.ui.components.OfflineBanner
import ch.ciuin.app.ui.components.RoundIconButton
import ch.ciuin.app.ui.components.RoundIconButtonStyle
import ch.ciuin.app.ui.components.SearchBar
import ch.ciuin.app.ui.components.TitleBlock
import ch.ciuin.app.ui.icons.CiuinIcons
import ch.ciuin.app.ui.place.PlaceSheet
import ch.ciuin.app.ui.route.RouteHeader
import ch.ciuin.app.ui.route.RouteSheet
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinMotion
import ch.ciuin.app.ui.theme.CiuinType
import ch.ciuin.app.ui.theme.LocalReduceMotion
import ch.ciuin.app.ui.toUi
import ch.ciuin.shared.api.NoisePeriod
import ch.ciuin.shared.model.Place
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private val categories = listOf(Category.PERSONAL, Category.SOUND, Category.LIGHT, Category.CROWD, Category.ACCESS)
private val layers = categories.map { if (it == Category.PERSONAL) "For you" else it.title }

@Composable
internal fun MapScreen(
    environment: AppEnvironment,
    data: MapData,
    state: MapState,
    tiles: TileCache,
    category: Category,
    onCategory: (Category) -> Unit,
    personalScores: Map<String, Double>,
    selected: Place?,
    onSelectPlace: (Place?) -> Unit,
    onSearch: () -> Unit,
    onMeasure: ((Place?) -> Unit)?,
    showMessage: suspend (String) -> Unit,
    tabBar: @Composable () -> Unit,
) {
    val period = data.noisePeriod
    val routes = environment.routes
    val route = routes.active
    val places = data.places
    val usesSonbase = remember(places) { places.any { it.baselineSource.equals("sonbase", ignoreCase = true) } }
    val byId = remember(places) { places.associateBy { it.placeId } }
    val placeRoute = remember(environment.location.current, selected) {
        environment.location.current?.let { here -> selected?.let { routeToPlace(here, it) } }
    }
    val listening = environment.listening
    val location = environment.location
    val userLocation = location.current
    val scope = rememberCoroutineScope()
    var locating by remember { mutableStateOf(false) }
    val chosenLayer = if (route == null) category else Category.PERSONAL
    val grid = data.grids[if (chosenLayer == Category.SOUND) period else NoisePeriod.DAY] ?: NoiseGrid()
    val noise = if (chosenLayer == Category.SOUND) period else NoisePeriod.DAY
    val lightGrid = data.readingGrids[ReadingLayer.Light]
    val flickerGrid = data.readingGrids[ReadingLayer.Flicker]
    val showFlicker = chosenLayer == Category.LIGHT && (data.preferFlicker
        ?: (lightGrid?.samples?.isEmpty() == true && flickerGrid?.samples?.isNotEmpty() == true))
    val measurementGrid = when (chosenLayer) {
        Category.LIGHT -> if (showFlicker) flickerGrid else lightGrid
        Category.CROWD -> data.readingGrids[ReadingLayer.Crowd]
        else -> null
    }
    val samples = remember(chosenLayer, places, grid, personalScores, noise, measurementGrid, showFlicker) {
        measurementGrid?.samples ?: if (showFlicker) emptyList()
        else samplesFor(chosenLayer, places, grid.points, personalScores, noise)
    }
    val cell = cellLevel(state.zoom, LAUSANNE_LAT, measurementGrid?.resolutionMeters ?: grid.resolutionMeters)
    val showUnknownCells = chosenLayer == Category.LIGHT && places.isNotEmpty()
    val placeCells = remember(places, cell, showUnknownCells) {
        if (!showUnknownCells) emptyList()
        else aggregateHex(places.map { HeatSample(lonToX(it.lon), latToY(it.lat), 0.0) }, hexRadius(cell))
    }
    val heat = remember(samples, cell, data.offline, chosenLayer, placeCells, showFlicker) {
        val measured = aggregateHex(samples, hexRadius(cell), Averaging.of(chosenLayer)).map {
            val level = if (showFlicker) flickerLevel(it.mean) else chosenLayer.levelOf(it.mean).toUi()
            HeatHex(it.x, it.y, it.radius, level.color.copy(alpha = level.hexAlpha(data.offline)))
        }
        if (placeCells.isEmpty()) measured
        else {
            val filled = measured.mapTo(hashSetOf()) { it.x to it.y }
            measured + placeCells.filter { (it.x to it.y) !in filled }.map {
                HeatHex(it.x, it.y, it.radius, CiuinColors.DottedLine, outline = true)
            }
        }
    }
    val personal = environment.personalModel
    val session = personal.state.session
    val origin = userLocation
    val nearest = remember(places, origin, session, data.usingSamplePlaces) {
        if (origin != null && session != null && !data.usingSamplePlaces) {
            nearestCalmSpot(places, origin, session::score)
        } else null
    }
    val overlay = remember(route, routes.walkingRoute) {
        route?.let { projectQuietPlaceRoute(it, routes.walkingRoute) }
    }
    val reduceMotion = LocalReduceMotion.current

    BoxWithConstraints(Modifier.fillMaxSize().background(CiuinColors.MapGround)) {
        val density = LocalDensity.current
        val viewport = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val maxInsightsHeight = maxHeight * 0.38f
        val tilePx = with(density) { 256.dp.toPx() }
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        val bottomInset = insets.calculateBottomPadding()
        val topInset = insets.calculateTopPadding()
        var routeHeaderPx by remember(routes.requestCount) { mutableIntStateOf(0) }
        var routeSheetPx by remember(routes.requestCount) { mutableIntStateOf(0) }
        val streetLevel = state.zoom >= PLACE_ZOOM
        val overviewPins = state.zoom >= 13.0
        var bounds by remember(viewport, tilePx) { mutableStateOf(mapBounds(state, viewport, tilePx)) }
        LaunchedEffect(data, bounds) { data.updateReadingBounds(bounds) }
        LaunchedEffect(state, viewport, tilePx) {
            snapshotFlow { mapBounds(state, viewport, tilePx) }.collectLatest {
                delay(180)
                bounds = it
            }
        }
        val visibleBounds = bounds
        val showHeat = data.showHeat
        val markers = remember(places, selected, chosenLayer, personalScores, nearest, streetLevel, route, noise, visibleBounds, overviewPins, showHeat, showFlicker) {
            val shown = when {
                route != null -> listOfNotNull(byId[route.destinationId])
                selected != null -> listOf(selected)
                streetLevel || !showHeat -> places
                !overviewPins -> emptyList()
                else -> sequenceOf(nearest?.place).filterNotNull()
                    .plus(places.asSequence().filter { visibleBounds.containsLocation(it.lat, it.lon) }
                        .sortedBy { place ->
                            val dx = lonToX(place.lon) - (visibleBounds.x0 + visibleBounds.x1) / 2
                            val dy = latToY(place.lat) - (visibleBounds.y0 + visibleBounds.y1) / 2
                            dx * dx + dy * dy
                        })
                    .distinctBy { it.placeId }.take(3).toList()
            }
            shown.map { place ->
                val value = chosenLayer.mapValue(place, personalScores[place.placeId], noise)
                val level = if (showFlicker) place.measured?.features?.flickerIndex?.let(::flickerLevel) ?: UiLevel.NoData
                else value?.let(chosenLayer::levelOf).toUi()
                MapMarker(place.placeId, place.lat, place.lon, place.name, level, showLabel = !streetLevel && showHeat)
            }
        }
        val routeTop = routeHeaderPx + with(density) { 12.dp.toPx() }
        val routeBottom = viewport.height - routeSheetPx - with(density) { 16.dp.toPx() }
        LaunchedEffect(overlay, routes.requestCount, viewport, tilePx, routeTop, routeBottom) {
            if (overlay != null && !viewport.isEmpty() && routeHeaderPx > 0 && routeSheetPx > 0 && routeBottom > routeTop) {
                state.fitRoute(overlay, Size(viewport.width * 0.75f, routeBottom - routeTop), tilePx)
                state.pan(Offset(0f, (routeTop + routeBottom - viewport.height) / 2f), tilePx)
            }
        }
        val readingGrids = data.readingGrids
        val insight = remember(places, personalScores, bounds, grid, noise, readingGrids) {
            areaInsight(
                places, grid.points, bounds, personalScores, noise,
                lightGrid = readingGrids[ReadingLayer.Light]?.samples,
                crowdGrid = readingGrids[ReadingLayer.Crowd]?.samples,
                flickerGrid = readingGrids[ReadingLayer.Flicker]?.samples,
            )
        }

        MapView(
            state = state,
            markers = markers,
            selectedId = selected?.placeId,
            onMarkerTap = { marker ->
                if (route == null) onSelectPlace(marker?.let { byId[it.id] })
            },
            modifier = Modifier.fillMaxSize(),
            heat = if (showHeat) heat else emptyList(),
            routeOverlay = overlay,
            userLocation = userLocation,
            layerKey = if (showHeat) Triple(chosenLayer, noise, showFlicker) else null,
            reduceMotion = reduceMotion,
            tileCache = tiles,
        )
        MapVeil(height = if (selected != null) topInset + 22.dp else topInset + 148.dp)
        if (route != null) {
            RouteHeader(
                route, routes.walkingRoute, routes::end,
                Modifier.align(Alignment.TopCenter).onSizeChanged { routeHeaderPx = it.height }
                    .padding(top = topInset + 2.dp, start = 16.dp, end = 16.dp),
            )
        } else if (selected == null) {
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = topInset + 2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SearchBar(onSearch, Modifier.weight(1f))
                    if (listening != null) {
                        ListeningButton(listening.isListening, { listening.setListening(!listening.isListening) })
                    }
                }
                LayerChipRow(layers, categories.indexOf(category), { onCategory(categories[it]) }, Modifier.padding(top = 12.dp))
                if (data.offline) {
                    OfflineBanner(
                        data::retry,
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                        subtitle = if (data.usingSamplePlaces) "Showing sample places. Map details may be missing."
                        else "Some map details may be missing",
                    )
                } else {
                    val liveStatus = listening?.message ?: personal.liveScore?.let {
                        "Live overwhelm ${(it * 100).roundToInt()}%"
                    } ?: "Waiting for live audio…".takeIf { listening?.isListening == true }
                    liveStatus?.let {
                        Text(
                            it,
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                .background(CiuinColors.Ground).padding(8.dp),
                            style = CiuinType.Caption,
                        )
                    }
                }
            }
        }

        val attribution = when {
            route != null && routes.walkingRoute != null -> "© OpenStreetMap · routing: OSRM"
            (chosenLayer == Category.PERSONAL || chosenLayer == Category.SOUND) && (usesSonbase || grid.points.isNotEmpty()) ->
                "© OpenStreetMap · noise: sonBASE © FOEN"
            else -> "© OpenStreetMap contributors"
        }
        val bottom = Modifier.align(Alignment.BottomCenter)
        when {
            selected != null && route == null -> {
                MapOverlay(bottom, attribution, reduceMotion, selected.placeId) {
                    PlaceSheet(
                        selected, environment, { onSelectPlace(null) },
                        placeRoute?.let { { routes.start(it) } },
                        onMeasure?.takeUnless { data.usingSamplePlaces }?.let { { it(selected) } },
                        bottomInset = bottomInset,
                    )
                }
            }
            route != null -> {
                MapOverlay(bottom.onSizeChanged { routeSheetPx = it.height }, attribution, reduceMotion, routes.requestCount) {
                    RouteSheet(
                        environment, route, routes.walkingRoute, routes.loading, byId[route.destinationId],
                        onEnd = routes::end,
                        onOkayNow = {
                            personal.recordFeedback(null, 0)
                            routes.end()
                        },
                        bottomInset = bottomInset,
                    )
                }
            }
            else -> {
                Column(bottom.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        AttributionPlate(attribution, Modifier.align(Alignment.BottomStart).padding(bottom = 8.dp))
                        Row(
                            Modifier.align(Alignment.BottomEnd).padding(bottom = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MapControls(
                                state, viewport, tilePx, showHeat,
                                showFlicker = showFlicker,
                                toggleFlicker = if (category == Category.LIGHT) ({ data.preferFlicker = !showFlicker }) else null,
                                toggleHeat = { data.showHeat = !showHeat },
                            )
                            RoundIconButton(
                                CiuinIcons.Gps01, "My location",
                                onClick = {
                                    if (!locating) scope.launch {
                                        locating = true
                                        try {
                                            val permission = environment.permissions.request(AppPermission.LOCATION)
                                            val point = if (permission == PermissionStatus.GRANTED) {
                                                withTimeoutOrNull(4_000) {
                                                    snapshotFlow { location.current }.filterNotNull().first()
                                                }
                                            } else null
                                            if (point == null) {
                                                showMessage("Your location is unavailable")
                                            } else {
                                                state.centerOn(point.lat, point.lon, maxOf(state.zoom, LAUSANNE_ZOOM))
                                            }
                                        } finally {
                                            locating = false
                                        }
                                    }
                                },
                                style = RoundIconButtonStyle.Floating,
                                enabled = !locating,
                            )
                        }
                    }
                    BottomSheetSurface(gap = if (data.offline) 14.dp else 16.dp) {
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = maxInsightsHeight)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            if (!data.loaded) {
                                TitleBlock("Loading Lausanne", eyebrow = "Around you")
                            } else {
                                MapInsights(
                                    category, insight, period, { data.noisePeriod = it },
                                    data.offline, environment.measurement?.pendingUploads ?: 0,
                                    listening != null, nearest, {
                                        origin?.let { here -> routeToPlace(here, it) }?.let(routes::start)
                                    },
                                    onMeasure?.let { start -> { start(null) } },
                                    liveFeedback = if (personal.liveScore != null) {
                                        { label -> personal.recordFeedback(null, label) }
                                    } else null,
                                    feedbackEnabled = !personal.state.busy,
                                    showFlicker = showFlicker,
                                )
                            }
                        }
                    }
                    tabBar()
                }
            }
        }
    }
}

@Composable
private fun MapOverlay(
    modifier: Modifier,
    attribution: String,
    reduceMotion: Boolean,
    key: Any,
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.key(key) {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }
        AnimatedVisibility(
            visible,
            modifier = modifier,
            enter = if (reduceMotion) fadeIn(CiuinMotion.reducedFade())
            else slideInVertically(CiuinMotion.sheetSpring()) { it },
        ) {
            Column(Modifier.fillMaxWidth()) {
                AttributionPlate(attribution, Modifier.padding(start = 16.dp, bottom = 8.dp))
                content()
            }
        }
    }
}

@Composable
private fun MapControls(
    state: MapState,
    viewport: Size,
    tilePx: Float,
    showHeat: Boolean,
    showFlicker: Boolean,
    toggleFlicker: (() -> Unit)?,
    toggleHeat: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        RoundIconButton(CiuinIcons.Maps, "Map controls", { open = true }, style = RoundIconButtonStyle.Floating)
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = CiuinColors.Ground,
        ) {
            val center = Offset(viewport.width / 2, viewport.height / 2)
            DropdownMenuItem({ Text("Zoom in", style = CiuinType.Label) }, {
                state.zoomAt(2.0, center, viewport, tilePx)
            }, enabled = state.zoom < MapState.MAX_ZOOM)
            DropdownMenuItem({ Text("Zoom out", style = CiuinType.Label) }, {
                state.zoomAt(0.5, center, viewport, tilePx)
            }, enabled = state.zoom > MapState.MIN_ZOOM)
            DropdownMenuItem({ Text("Center on Lausanne", style = CiuinType.Label) }, {
                state.centerOn(LAUSANNE_LAT, LAUSANNE_LON, LAUSANNE_ZOOM)
                open = false
            })
            DropdownMenuItem({ Text(if (showHeat) "Hide heatmap" else "Show heatmap", style = CiuinType.Label) }, {
                toggleHeat()
                open = false
            })
            if (toggleFlicker != null) {
                DropdownMenuItem({ Text(if (showFlicker) "Show brightness" else "Show flicker", style = CiuinType.Label) }, {
                    toggleFlicker()
                    open = false
                })
            }
        }
    }
}

private fun mapBounds(state: MapState, viewport: Size, tilePx: Float): ViewBounds =
    state.visibleBounds(viewport, tilePx).let { ViewBounds(it[0], it[1], it[2], it[3]) }

private fun UiLevel.hexAlpha(offline: Boolean): Float = when (this) {
    UiLevel.Calm -> if (offline) CiuinColors.HexAlphaCalmSaved else CiuinColors.HexAlphaCalm
    UiLevel.Moderate -> if (offline) CiuinColors.HexAlphaModerateSaved else CiuinColors.HexAlphaModerate
    UiLevel.Loud -> if (offline) CiuinColors.HexAlphaLoudSaved else CiuinColors.HexAlphaLoud
    UiLevel.NoData -> 0f
}
