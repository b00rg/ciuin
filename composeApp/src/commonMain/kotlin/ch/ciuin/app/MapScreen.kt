package ch.ciuin.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import ch.ciuin.app.map.MapMarker
import ch.ciuin.app.map.MapState
import ch.ciuin.app.map.MapView
import ch.ciuin.shared.api.BBox
import ch.ciuin.shared.api.FallbackPlacesApi
import ch.ciuin.shared.api.PlacesApi
import ch.ciuin.shared.api.Stats
import ch.ciuin.shared.model.Place
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private val QUIET = Color(0xFF2E7D32)
private val MEDIUM = Color(0xFFF9A825)
private val LOUD = Color(0xFFC62828)
private val UNKNOWN = Color(0xFF9E9E9E)

/**
 * How overwhelming a place is, in [0, 1], or null if we know nothing.
 * Placeholder until Paramveer's PersonalModel lands: then pass `{ model.score(featuresOf(it)) }`
 * so markers are coloured by **this user's** p_overwhelm.
 */
fun noiseLevel(p: Place): Double? =
    (p.measured?.features?.dbMean ?: p.baselineNoiseDb)?.let { ((it - 40.0) / 35.0).coerceIn(0.0, 1.0) }

fun levelColor(level: Double?): Color = when {
    level == null -> UNKNOWN
    level < 0.5 -> lerp(QUIET, MEDIUM, (level / 0.5).toFloat())
    else -> lerp(MEDIUM, LOUD, ((level - 0.5) / 0.5).toFloat())
}

@Composable
fun MapScreen(
    api: PlacesApi,
    score: (Place) -> Double? = ::noiseLevel,
    onMeasure: (Place) -> Unit = {},
    refreshMs: Long = 2_000,
) {
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var stats by remember { mutableStateOf<Stats?>(null) }
    var offline by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val mapState = remember { MapState(lat = 46.5197, lon = 6.6323, zoom = 14.0) }

    // Poll so a measurement taken on the phone shows up on the desktop map within ~2 s.
    LaunchedEffect(api) {
        while (true) {
            try {
                places = api.places(BBox.LAUSANNE)
                stats = api.stats()
                // FallbackPlacesApi never throws; it answers from bundled data and flags itself offline.
                offline = (api as? FallbackPlacesApi)?.online?.value == false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                offline = true // keep showing the last places we had
            }
            delay(refreshMs)
        }
    }

    val markers = places.map { MapMarker(it.placeId, it.lat, it.lon, it.name, levelColor(score(it))) }
    val selected = places.firstOrNull { it.placeId == selectedId }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = with(LocalDensity.current) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val tilePx = 256 * LocalDensity.current.density

        MapView(
            state = mapState,
            markers = markers,
            selectedId = selectedId,
            onMarkerTap = { selectedId = it?.id },
            modifier = Modifier.fillMaxSize(),
        )

        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
            // Top: title + gap badge
            Column(Modifier.align(Alignment.TopStart)) {
                Pill {
                    Text("Ciúin · Sensory Map", style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.size(6.dp))
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
                    Pill(Color(0xFFFFE0B2)) { Text("Backend unreachable: showing offline data",style = MaterialTheme.typography.labelMedium) }
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
                SmallFloatingActionButton(onClick = { mapState.centerOn(46.5197, 6.6323, 14.0) }) { Text("⌂") }
            }

            // Bottom: place card or legend, then attribution (required by OSM)
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (selected != null) {
                    PlaceCard(selected, score(selected), onMeasure = { onMeasure(selected) }, onClose = { selectedId = null })
                } else {
                    Legend(Modifier.align(Alignment.Start))
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    "© OpenStreetMap contributors",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End)
                        .background(Color.White.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun Pill(color: Color = Color.White.copy(alpha = 0.92f), content: @Composable () -> Unit) {
    Surface(color = color, shape = RoundedCornerShape(50), shadowElevation = 2.dp) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { content() }
    }
}

@Composable
private fun Legend(modifier: Modifier = Modifier) {
    Surface(modifier, color = Color.White.copy(alpha = 0.92f), shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LegendItem(QUIET, "Calm")
            LegendItem(MEDIUM, "Busy")
            LegendItem(LOUD, "Intense")
            LegendItem(UNKNOWN, "No data")
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.size(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** Minimal place detail. The full Place detail screen may move to Siddhi after 19:00. */
@Composable
private fun PlaceCard(place: Place, level: Double?, onMeasure: () -> Unit, onClose: () -> Unit) {
    Card(
        Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(levelColor(level), CircleShape))
                Spacer(Modifier.size(8.dp))
                Text(place.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            Text(place.category.replace('_', ' '), style = MaterialTheme.typography.bodyMedium)

            val noise = place.measured?.let { "Measured ${it.features.dbMean.fmt()} dB (n=${it.n})" }
                ?: place.baselineNoiseDb?.let { "Baseline ${it.fmt()} dB (${place.baselineSource ?: "?"})" }
                ?: "No noise data yet"
            Text(noise, style = MaterialTheme.typography.bodySmall)

            val tags = buildList {
                place.wheelchair?.let { add("Wheelchair: $it") }
                if (place.quietRoom) add("Quiet room")
                place.sensoryFriendly?.let { add("Sensory-friendly: $it") }
            }
            if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.size(4.dp))
            Button(onClick = onMeasure) { Text("Measure here") }
        }
    }
}

private fun Double.fmt(): String = ((this * 10).toInt() / 10.0).toString()
