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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
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
import ch.ciuin.shared.api.BBox
import ch.ciuin.shared.api.FallbackPlacesApi
import ch.ciuin.shared.api.PlacesApi
import ch.ciuin.shared.api.Stats
import ch.ciuin.shared.model.HeatPoint
import ch.ciuin.shared.model.Place
import ch.ciuin.shared.model.parseHeatGrid
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
fun MapScreen(
    api: PlacesApi,
    onMeasure: (Place) -> Unit = {},
    refreshMs: Long = 2_000,
) {
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var grid by remember { mutableStateOf<List<HeatPoint>>(emptyList()) }
    var gridResM by remember { mutableStateOf(DEFAULT_RES_M) }
    var stats by remember { mutableStateOf<Stats?>(null) }
    var offline by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf(Category.SOUND) }
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
                gridResM = (json["res_m"] as? JsonPrimitive)?.doubleOrNull ?: DEFAULT_RES_M
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // keep the last grid
            }
            delay(60_000)
        }
    }

    val selected = places.firstOrNull { it.placeId == selectedId }
    val markers = if (mapState.zoom >= MARKERS_ZOOM) {
        places.map { MapMarker(it.placeId, it.lat, it.lon, it.name, category.level(it).color()) }
    } else {
        emptyList()
    }

    val samples = remember(category, places, grid) { samplesFor(category, places, grid) }
    val level = cellLevel(mapState.zoom, CITY_LAT, gridResM)
    // Honeycomb: hexagons shrink as you zoom in (never finer than the data); each is one solid band colour.
    val heat = remember(samples, level, category) {
        aggregateHex(samples, hexRadius(level), Averaging.of(category)).map {
            HeatHex(it.x, it.y, it.radius, heatBand(category.heat(it.mean)).color())
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = with(LocalDensity.current) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val tilePx = 256 * LocalDensity.current.density

        val cityView = mapState.zoom < CITY_OVERVIEW_ZOOM
        val bounds = if (cityView) null else mapState.visibleBounds(viewport, tilePx).let { ViewBounds(it[0], it[1], it[2], it[3]) }
        val summary = summarise(category, places, grid, bounds)

        MapView(
            state = mapState,
            markers = markers,
            selectedId = selectedId,
            onMarkerTap = { selectedId = it?.id },
            modifier = Modifier.fillMaxSize(),
            heat = heat,
        )

        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
            // Top: title, category chips, gap badge
            Column(Modifier.align(Alignment.TopStart).fillMaxWidth()) {
                Pill { Text("Ciúin · Sensory Map", style = MaterialTheme.typography.titleSmall) }
                Spacer(Modifier.size(6.dp))
                CategoryChips(category, onSelect = { category = it })
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
                when {
                    selected != null ->
                        PlaceCard(selected, category, onMeasure = { onMeasure(selected) }, onClose = { selectedId = null })
                    else ->
                        AreaPanel(category, summary, if (cityView) "$CITY_NAME overall" else "This area")
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryChips(selected: Category, onSelect: (Category) -> Unit) {
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
    Card(
        Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("$title · ${category.title}", style = MaterialTheme.typography.titleMedium)
                    Text(headline(category, summary), style = MaterialTheme.typography.bodyMedium)
                }
                Text(if (expanded) "Hide" else "Details", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            BandKey(category)
            if (expanded) {
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
private fun PlaceCard(place: Place, current: Category, onMeasure: () -> Unit, onClose: () -> Unit) {
    Card(
        Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            Button(onClick = onMeasure) { Text("Measure here") }
        }
    }
}
