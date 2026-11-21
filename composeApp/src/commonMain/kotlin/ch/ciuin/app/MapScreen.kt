package ch.ciuin.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import ch.ciuin.app.heat.AreaSummary
import ch.ciuin.app.heat.Averaging
import ch.ciuin.app.heat.ViewBounds
import ch.ciuin.app.heat.aggregateHex
import ch.ciuin.app.heat.cellLevel
import ch.ciuin.app.heat.hexRadius
import ch.ciuin.app.heat.samplesFor
import ch.ciuin.app.heat.summarise
import ch.ciuin.app.map.HeatHex
import ch.ciuin.app.map.MapMarker
import ch.ciuin.app.map.MapState
import ch.ciuin.app.map.MapView
import ch.ciuin.app.map.projectQuietPlaceRoute
import ch.ciuin.app.personal.PersonalModelSession
import ch.ciuin.app.route.QuietPlaceRoute
import ch.ciuin.app.route.WalkingRoute
import ch.ciuin.shared.api.BBox
import ch.ciuin.shared.api.FallbackPlacesApi
import ch.ciuin.shared.api.PlacesApi
import ch.ciuin.shared.api.Stats
import ch.ciuin.shared.model.FeatureVector
import ch.ciuin.shared.model.HeatPoint
import ch.ciuin.shared.model.Place
import ch.ciuin.shared.model.parseHeatGrid
import ch.ciuin.shared.personal.PersonalPreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

private const val CITY_NAME = "Lausanne"
private const val CITY_LAT = 46.5197
private const val CITY_LON = 6.6323
private const val CITY_ZOOM = 14.0

/** Below this zoom the area panel describes the whole city rather than what's on screen. */
private const val CITY_OVERVIEW_ZOOM = 13.0

/** Place markers appear from this zoom on: street level, where the city centre's dots stop crowding. */
private const val MARKERS_ZOOM = 18.0

/** Used for cell sizing when the grid doesn't say (and when there's no grid: places are ~this dense). */
private const val DEFAULT_RES_M = 50.0

/**
 * Main map: a heatmap for the selected [Category], with place markers once zoomed in to street level;
 * the bottom panel summarises the city or the area on screen.
 */
@Composable
internal fun MapScreen(
    api: PlacesApi,
    personal: PersonalModelSession?,
    personalSnackbar: SnackbarHostState,
    personalBusy: Boolean,
    liveListening: Boolean,
    liveScore: Double?,
    onLiveListeningChange: ((Boolean) -> Unit)?,
    liveListenerMessage: String?,
    onSelectPreset: (PersonalPreset) -> Unit,
    onFeedback: (FeatureVector?, Int) -> Unit,
    onResetPersonal: () -> Unit,
    onRetryPersonal: () -> Unit,
    quietPlaceRoute: QuietPlaceRoute? = null,
    onClearQuietPlaceRoute: () -> Unit = {},
    quietPlaceRouteRequest: Int = 0,
    onMeasure: (Place) -> Unit = {},
    refreshMs: Long = 2_000,
    walkingRoute: WalkingRoute? = null,
    walkingRouteLoading: Boolean = false,
) {
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var grid by remember { mutableStateOf<List<HeatPoint>>(emptyList()) }
    var gridResM by remember { mutableStateOf(DEFAULT_RES_M) }
    var stats by remember { mutableStateOf<Stats?>(null) }
    var offline by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf(Category.SOUND) }
    var heatOn by remember { mutableStateOf(true) }
    var personalSettingsOpen by remember { mutableStateOf(false) }
    val mapState = remember { MapState(lat = CITY_LAT, lon = CITY_LON, zoom = CITY_ZOOM) }

    // Poll so a measurement taken on the phone shows up on the desktop map within ~2 s.
    LaunchedEffect(api) {
        while (true) {
            try {
                // Parse off the main thread: the full Lausanne list is ~1 MB of JSON.
                val fresh = withContext(Dispatchers.Default) { api.places(BBox.LAUSANNE) }
                if (fresh != places) places = fresh // unchanged poll: don't rebuild the heatmap
                stats = withContext(Dispatchers.Default) { api.stats() }
                // FallbackPlacesApi never throws; it answers from offline data and flags itself offline.
                offline = (api as? FallbackPlacesApi)?.online?.value == false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                offline = true // keep showing the last places we had
            }
            delay(refreshMs)
        }
    }
    // The baseline grid is static: fetch it, then refresh rarely (picks it up once the backend has built it).
    LaunchedEffect(api) {
        while (true) {
            try {
                val json = withContext(Dispatchers.Default) { api.heatmap(BBox.LAUSANNE, "noise") }
                grid = withContext(Dispatchers.Default) { parseHeatGrid(json) }
                gridResM = (json["cell_m"] as? JsonPrimitive)?.doubleOrNull
                    ?: (json["res_m"] as? JsonPrimitive)?.doubleOrNull
                    ?: DEFAULT_RES_M
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // keep the last grid
            }
            delay(60_000)
        }
    }

    val selected = places.firstOrNull { it.placeId == selectedId }
    val personalScores = remember(category, places, personal) {
        if (category != Category.PERSONAL || personal == null) emptyMap()
        else buildMap {
            for (place in places) {
                val features = place.personalFeatures ?: continue
                put(place.placeId, personal.score(features))
            }
        }
    }
    val showMarkers = !heatOn || mapState.zoom >= MARKERS_ZOOM
    val markers = remember(places, category, personalScores, showMarkers) {
        if (!showMarkers) emptyList()
        else places.map { place ->
            val level = category.level(place, personalScores[place.placeId])
            MapMarker(place.placeId, place.lat, place.lon, place.name, level.color())
        }
    }

    val samples = remember(category, places, grid, personalScores) { samplesFor(category, places, grid, personalScores) }
    val level = cellLevel(mapState.zoom, CITY_LAT, gridResM)
    val heat = remember(samples, level, category, heatOn) {
        if (!heatOn) emptyList()
        else aggregateHex(samples, hexRadius(level), Averaging.of(category)).map {
            HeatHex(it.x, it.y, it.radius, heatBand(category.heat(it.mean)).color())
        }
    }
    val routeOverlay = remember(quietPlaceRoute, walkingRoute) {
        quietPlaceRoute?.let { projectQuietPlaceRoute(it, walkingRoute) }
    }
    LaunchedEffect(quietPlaceRoute, quietPlaceRouteRequest) {
        if (quietPlaceRoute != null) {
            selectedId = null
            personalSettingsOpen = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = with(LocalDensity.current) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val tilePx = 256 * LocalDensity.current.density
        LaunchedEffect(routeOverlay, quietPlaceRouteRequest, viewport, tilePx) {
            if (routeOverlay != null && !viewport.isEmpty()) {
                // Leave room around the route for the map controls and route card.
                val routeArea = Size(viewport.width * 0.75f, viewport.height * 0.45f)
                mapState.fitRoute(routeOverlay, routeArea, tilePx)
            }
        }

        val cityView = mapState.zoom < CITY_OVERVIEW_ZOOM
        val bounds = if (cityView) null else mapState.visibleBounds(viewport, tilePx).let { ViewBounds(it[0], it[1], it[2], it[3]) }
        val summary = remember(category, places, grid, bounds, personalScores) {
            summarise(category, places, grid, bounds, personalScores)
        }
        val placeCardHeight = maxHeight * if (quietPlaceRoute == null) 0.72f else 0.48f

        MapView(
            state = mapState,
            markers = markers,
            selectedId = selectedId,
            onMarkerTap = { selectedId = it?.id },
            modifier = Modifier.fillMaxSize(),
            heat = heat,
            routeOverlay = routeOverlay,
        )

        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
            // Top: title, category chips, gap badge
            Column(Modifier.align(Alignment.TopStart).fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Pill { Text("Ciúin · Sensory Map", style = MaterialTheme.typography.titleSmall) }
                    Surface(
                        onClick = { personalSettingsOpen = true },
                        color = Color.White.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(50),
                        shadowElevation = 2.dp,
                    ) {
                        Text(
                            "Personal settings",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.size(6.dp))
                CategoryChips(category, onSelect = { category = it }, heatOn = heatOn, onHeatToggle = { heatOn = !heatOn })
                if (onLiveListeningChange != null) {
                    LiveListenerControls(liveListening, liveScore, liveListenerMessage, onLiveListeningChange)
                }
                Spacer(Modifier.size(2.dp))
                stats?.let { s ->
                    Pill {
                        Text(
                            "${s.placesMapped} places · ${s.measurementsTaken} measured · " +
                                "${s.sensoryFriendlyTags} sensory-friendly tags",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                if (offline) {
                    Spacer(Modifier.size(6.dp))
                    Pill(Color(0xFFFFE0B2)) { Text("Backend unreachable: showing offline data", style = MaterialTheme.typography.labelMedium) }
                }
            }

            // Right: zoom controls
            Column(
                Modifier.align(Alignment.CenterEnd),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val centre = Offset(viewport.width / 2, viewport.height / 2)
                SmallFloatingActionButton(onClick = { mapState.zoomAt(2.0, centre, viewport, tilePx) }) { Text("+") }
                SmallFloatingActionButton(onClick = { mapState.zoomAt(0.5, centre, viewport, tilePx) }) { Text("−") }
                SmallFloatingActionButton(onClick = { mapState.centerOn(CITY_LAT, CITY_LON, CITY_ZOOM) }) { Text("⌂", fontFamily = symbolFontFamily) }
            }

            // Bottom: place card or area summary; then attribution (required by OSM)
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!personalSettingsOpen) SnackbarHost(personalSnackbar, Modifier.widthIn(max = 480.dp))
                if (quietPlaceRoute != null) {
                    QuietPlaceRouteCard(quietPlaceRoute, walkingRoute, walkingRouteLoading, onClearQuietPlaceRoute)
                    Spacer(Modifier.size(6.dp))
                }
                if (selected == null && liveScore != null) {
                    Surface(shape = RoundedCornerShape(12.dp), shadowElevation = 4.dp) {
                        Box(Modifier.padding(horizontal = 12.dp)) {
                            PersonalFeedbackButtons(enabled = !personalBusy) { onFeedback(null, it) }
                        }
                    }
                    Spacer(Modifier.size(6.dp))
                }
                when {
                    selected != null ->
                        PlaceCard(
                            selected,
                            category,
                            onMeasure = { onMeasure(selected) },
                            onClose = { selectedId = null },
                            modifier = Modifier.heightIn(max = placeCardHeight),
                        ) {
                            PersonalPlaceFeedback(selected, personal, personalBusy, liveScore, onFeedback)
                        }
                    quietPlaceRoute != null -> Unit
                    heatOn ->
                        AreaPanel(category, summary, if (cityView) "$CITY_NAME overall" else "This area")
                    else -> Unit
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    "© OpenStreetMap contributors · noise: sonBASE © FOEN",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End)
                        .background(Color.White.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
    if (personalSettingsOpen) {
        PersonalSettingsDialog(
            personal = personal,
            busy = personalBusy,
            snackbar = personalSnackbar,
            onSelectPreset = onSelectPreset,
            onReset = onResetPersonal,
            onRetry = onRetryPersonal,
            onClose = { personalSettingsOpen = false },
        )
    }
}

@Composable
private fun QuietPlaceRouteCard(
    route: QuietPlaceRoute,
    walkingRoute: WalkingRoute?,
    walkingRouteLoading: Boolean,
    onClearRoute: () -> Unit,
) {
    Card(
        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(route.destinationName, style = MaterialTheme.typography.titleMedium)
                if (walkingRoute != null) {
                    Text("${walkingRoute.distanceMeters.roundToInt()} m walking", style = MaterialTheme.typography.bodyMedium)
                    val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
                    Text(
                        buildAnnotatedString {
                            append("OSRM routing · ")
                            withLink(LinkAnnotation.Url("https://www.openstreetmap.org/fixthemap", TextLinkStyles(style = linkStyle))) {
                                append("Fix the map")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else {
                    Text("${route.distanceMeters.roundToInt()} m straight line", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (walkingRouteLoading) "Finding a walking route…" else "Walking route unavailable",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            TextButton(onClick = onClearRoute) { Text("Clear route") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveListenerControls(
    listening: Boolean,
    score: Double?,
    message: String?,
    onListeningChange: (Boolean) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = listening,
            onClick = { onListeningChange(!listening) },
            label = { Text(if (listening) "Stop listening" else "Start listening") },
            colors = FilterChipDefaults.filterChipColors(containerColor = Color.White.copy(alpha = 0.92f)),
        )
        val status = when {
            message != null -> message
            score != null -> "Live overwhelm ${(score * 100).roundToInt()}%"
            listening -> "Waiting for live audio…"
            else -> null
        }
        status?.let { Pill { Text(it, style = MaterialTheme.typography.labelMedium) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryChips(selected: Category, onSelect: (Category) -> Unit, heatOn: Boolean, onHeatToggle: () -> Unit) {
    val colors = FilterChipDefaults.filterChipColors(
        containerColor = Color.White.copy(alpha = 0.92f),
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    )
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (c in Category.entries) {
            FilterChip(selected = c == selected, onClick = { onSelect(c) }, label = { Text(c.title) }, colors = colors)
        }
        FilterChip(selected = heatOn, onClick = onHeatToggle, label = { Text("Heatmap") }, colors = colors)
    }
}

@Composable
private fun Pill(color: Color = Color.White.copy(alpha = 0.92f), content: @Composable () -> Unit) {
    Surface(color = color, shape = RoundedCornerShape(50), shadowElevation = 2.dp) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { content() }
    }
}

/** Bottom panel in heatmap mode: city / area average for the category, a gradient key, and the breakdown. */
@Composable
private fun AreaPanel(category: Category, summary: AreaSummary, title: String) {
    var expanded by remember { mutableStateOf(true) }
    val hasDetails = category != Category.PERSONAL
    Card(
        Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val toggle = if (hasDetails) Modifier.clickable { expanded = !expanded } else Modifier
            Row(Modifier.fillMaxWidth().then(toggle), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("$title · ${category.title}", style = MaterialTheme.typography.titleMedium)
                    Text(headline(category, summary), style = MaterialTheme.typography.bodyMedium)
                }
                if (hasDetails) {
                    Text(if (expanded) "Hide" else "Details", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            BandKey(category)
            if (hasDetails && expanded) {
                if (summary.sections.isEmpty()) {
                    Text(
                        if (summary.mean == null) "Nothing measured here yet. Zoom out, or tap a place and use \"Measure here\"."
                        else "No breakdown yet: it fills in as places here get measured.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                for (section in summary.sections) {
                    Spacer(Modifier.size(2.dp))
                    Text(section.heading, style = MaterialTheme.typography.labelLarge)
                    for (share in section.shares) ShareBar(share.label, share.fraction)
                }
            }
        }
    }
}

private fun headline(category: Category, s: AreaSummary): String {
    val mean = s.mean ?: return "No ${category.title.lowercase()} data here yet"
    val levelName = category.levelName(category.levelOf(mean))
    return when (category) {
        Category.SOUND -> "Average ${mean.roundToInt()} dB · $levelName"
        Category.LIGHT -> "Average ${mean.roundToInt()} lux · $levelName (${s.sampleCount} measured places)"
        Category.CROWD -> "About ${mean.roundToInt()} people on average · $levelName (${s.sampleCount} places)"
        Category.PERSONAL -> "Average predicted overwhelm ${(mean * 100).roundToInt()}% · ${s.sampleCount} places"
        // An average of yes/limited/no reads badly ("mostly limited" at 65% yes), so state the share instead.
        Category.ACCESS -> {
            val accessible = s.sections.firstOrNull()?.shares?.firstOrNull { it.label == "Accessible" }?.fraction ?: 0.0
            "${(accessible * 100).roundToInt()}% wheelchair accessible (${s.sampleCount} tagged places)"
        }
    }
}

/** Key for the three heatmap bands, in the same colours as the map. */
@Composable
private fun BandKey(category: Category) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        for (level in Level.entries) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 18.dp, height = 10.dp).clip(RoundedCornerShape(3.dp)).background(level.color()))
                Spacer(Modifier.size(4.dp))
                Text(category.levelName(level), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ShareBar(label: String, fraction: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(120.dp))
        Box(
            Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Text(
            "${(fraction * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(44.dp).padding(start = 8.dp),
        )
    }
}

@Composable
private fun Dot(color: Color, sizeDp: Int) = Box(Modifier.size(sizeDp.dp).background(color, CircleShape))

/** Minimal place detail: every category at a glance. The full Place detail screen may move to Siddhi after 19:00. */
@Composable
private fun PlaceCard(
    place: Place,
    current: Category,
    onMeasure: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    personalFeedback: @Composable () -> Unit,
) {
    Card(
        modifier.widthIn(max = 480.dp).fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(place.name, style = MaterialTheme.typography.titleMedium)
                    val sub = buildList {
                        add(place.category.replace('_', ' '))
                        place.measured?.let { add("${it.n} measurement${if (it.n == 1) "" else "s"}") }
                    }
                    Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onClose) { Text("Close") }
            }
            Spacer(Modifier.size(4.dp))

            for (c in Category.entries) {
                if (c == Category.PERSONAL) continue
                val level = c.level(place)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(level.color(), 12)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        c.title,
                        style = if (c == current) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(64.dp),
                    )
                    Text(c.levelName(level), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
                    Text(c.detail(place), style = MaterialTheme.typography.bodySmall, color = Color(0xFF616161))
                }
            }

            val badges = buildList {
                if (place.quietRoom) add("Quiet room")
                place.sensoryFriendly?.let { add("Sensory-friendly: $it") }
            }
            if (badges.isNotEmpty()) Text(badges.joinToString(" · "), style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.size(4.dp))
            personalFeedback()
            Button(onClick = onMeasure) { Text("Measure here") }
        }
    }
}
