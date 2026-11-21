package ch.ciuin.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.ciuin.shared.api.BBox
import ch.ciuin.shared.api.FakePlacesApi
import ch.ciuin.shared.api.PlacesApi
import ch.ciuin.shared.model.Place
import ch.ciuin.shared.platformName

@Composable
fun App(api: PlacesApi = remember { FakePlacesApi() }) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            MapScreen(api)
        }
    }
}

/** Placeholder map screen: lists places until the real map lands. */
@Composable
fun MapScreen(api: PlacesApi) {
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    LaunchedEffect(api) { places = api.places(BBox.LAUSANNE) }

    // safeDrawing: keep clear of the status/navigation bars (Android draws edge-to-edge by default).
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
        Text("Sensory Map (placeholder)", style = MaterialTheme.typography.headlineSmall)
        Text(platformName(), style = MaterialTheme.typography.bodySmall)
        LazyColumn {
            items(places, key = { it.placeId }) { p ->
                ListItem(
                    headlineContent = { Text(p.name) },
                    supportingContent = {
                        Text("${p.category} · ${p.lat}, ${p.lon} · baseline ${p.baselineNoiseDb ?: "?"} dB")
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
