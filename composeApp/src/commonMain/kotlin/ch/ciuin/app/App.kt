package ch.ciuin.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import ch.ciuin.shared.api.PlacesApi

@Composable
fun App(api: PlacesApi = remember { createPlacesApi() }) {
    MaterialTheme(typography = appTypography()) {
        Surface(Modifier.fillMaxSize()) {
            MapScreen(api)
        }
    }
}
