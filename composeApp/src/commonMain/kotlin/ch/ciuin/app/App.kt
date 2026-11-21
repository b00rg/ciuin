package ch.ciuin.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import ch.ciuin.shared.api.FakePlacesApi
import ch.ciuin.shared.api.FallbackPlacesApi
import ch.ciuin.shared.api.KtorPlacesApi
import ch.ciuin.shared.api.PlacesApi

/**
 * Backend base URL for this platform, e.g. "http://192.168.43.10:8000".
 * Desktop: env CIUIN_API_URL or `-Pciuin.apiUrl=...`; Android: `-Pciuin.apiUrl=...` at build time.
 */
expect fun apiBaseUrl(): String

@Composable
fun App(api: PlacesApi = remember { FallbackPlacesApi(KtorPlacesApi(apiBaseUrl()), FakePlacesApi()) }) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            MapScreen(api)
        }
    }
}
