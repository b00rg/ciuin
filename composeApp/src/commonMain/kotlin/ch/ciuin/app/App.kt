package ch.ciuin.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.ciuin.shared.api.BBox
import ch.ciuin.shared.api.PlacesApi
import ch.ciuin.shared.model.Place
import ch.ciuin.shared.platformName
import kotlinx.coroutines.CancellationException

@Composable
fun App(api: PlacesApi = remember { createPlacesApi() }) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            MapScreen(api)
        }
    }
}

private sealed interface LoadState {
    data object Loading : LoadState
    data class Loaded(val places: List<Place>) : LoadState
    data class Failed(val message: String) : LoadState
}

/** Placeholder map screen: lists places until the real map lands. */
@Composable
fun MapScreen(api: PlacesApi) {
    var state by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(api, reload) {
        state = LoadState.Loading
        state = try {
            LoadState.Loaded(api.places(BBox.LAUSANNE).sortedBy { it.name })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadState.Failed(e.message ?: e::class.simpleName ?: "error")
        }
    }

    // safeDrawing: keep clear of the status/navigation bars (Android draws edge-to-edge by default).
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
        Text("Sensory Map (placeholder)", style = MaterialTheme.typography.headlineSmall)
        Text("${platformName()} · ${describeApi(api)}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        when (val s = state) {
            LoadState.Loading -> Text("Loading places…")
            is LoadState.Failed -> Column {
                Text("Couldn't reach the backend: ${s.message}", color = MaterialTheme.colorScheme.error)
                Text(
                    "Is it running? cd backend && uvicorn main:app --host 0.0.0.0 --port 8000",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { reload++ }) { Text("Retry") }
            }
            is LoadState.Loaded -> Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${s.places.size} places", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.padding(horizontal = 8.dp))
                    Button(onClick = { reload++ }) { Text("Refresh") }
                }
                LazyColumn {
                    items(s.places, key = { it.placeId }) { p -> PlaceRow(p) }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(p: Place) {
    val noise = p.baselineNoiseDb?.let { "baseline $it dB" } ?: "no baseline"
    val measured = p.measured?.let { " · measured ×${it.n}, ${it.features.dbMean} dB" } ?: ""
    ListItem(
        headlineContent = { Text(p.name) },
        supportingContent = { Text("${p.category} · $noise$measured · wheelchair: ${p.wheelchair ?: "?"}") },
    )
    HorizontalDivider()
}
