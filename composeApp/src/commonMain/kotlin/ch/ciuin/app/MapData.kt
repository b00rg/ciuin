package ch.ciuin.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ch.ciuin.shared.api.BBox
import ch.ciuin.shared.api.FallbackPlacesApi
import ch.ciuin.shared.api.NoisePeriod
import ch.ciuin.shared.api.PlaceRead
import ch.ciuin.shared.api.PlacesApi
import ch.ciuin.shared.api.heatmapAvailable
import ch.ciuin.shared.model.HeatPoint
import ch.ciuin.shared.model.Place
import ch.ciuin.shared.model.parseHeatGrid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

internal const val LAUSANNE_LAT = 46.5197
internal const val LAUSANNE_LON = 6.6323
internal const val LAUSANNE_ZOOM = 15.0
internal const val PLACE_ZOOM = 18.0
internal const val DEFAULT_RESOLUTION_METERS = 50.0

internal data class NoiseGrid(
    val points: List<HeatPoint> = emptyList(),
    val resolutionMeters: Double = DEFAULT_RESOLUTION_METERS,
)

internal class MapData {
    private var hasLivePlaces = false
    var places by mutableStateOf<List<Place>>(emptyList())
        private set
    var offline by mutableStateOf(false)
        private set
    var usingSamplePlaces by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var noisePeriod by mutableStateOf(NoisePeriod.DAY)
    var showHeat by mutableStateOf(true)
    var grids by mutableStateOf<Map<NoisePeriod, NoiseGrid>>(emptyMap())
        private set
    var retryCount by mutableIntStateOf(0)
        private set

    fun retry() { retryCount++ }

    fun updatePlace(updated: Place) {
        places = if (places.any { it.placeId == updated.placeId }) {
            places.map { if (it.placeId == updated.placeId) updated else it }
        } else {
            places + updated
        }
    }

    suspend fun refreshPlaces(api: PlacesApi) {
        try {
            val read = withContext(Dispatchers.Default) {
                if (api is FallbackPlacesApi) api.readPlaces(BBox.LAUSANNE)
                else PlaceRead(api.places(BBox.LAUSANNE), fromPrimary = true)
            }
            if (read.fromPrimary || !hasLivePlaces) places = read.data
            hasLivePlaces = hasLivePlaces || read.fromPrimary
            offline = !read.fromPrimary
            usingSamplePlaces = !hasLivePlaces
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            offline = true
        }
        loaded = true
    }

    suspend fun refreshNoise(api: PlacesApi, period: NoisePeriod) {
        try {
            val read = withContext(Dispatchers.Default) {
                if (api is FallbackPlacesApi) api.readHeatmap(BBox.LAUSANNE, "noise", period)
                else PlaceRead(api.heatmap(BBox.LAUSANNE, "noise", period), fromPrimary = true)
            }
            if (!read.fromPrimary) return
            val grid = withContext(Dispatchers.Default) {
                val json = read.data
                if (!json.heatmapAvailable) return@withContext null
                val resolution = (json["cell_m"] as? JsonPrimitive)?.doubleOrNull
                    ?: (json["res_m"] as? JsonPrimitive)?.doubleOrNull
                NoiseGrid(
                    parseHeatGrid(json),
                    resolution?.takeIf { it.isFinite() && it > 0 } ?: DEFAULT_RESOLUTION_METERS,
                )
            }
            grids = if (grid == null) grids - period else grids + (period to grid)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the last grid for this period while reconnecting.
        }
    }
}

@Composable
internal fun rememberMapData(api: PlacesApi, showSound: Boolean): MapData {
    val data = remember(api) { MapData() }
    LaunchedEffect(data, data.retryCount) {
        do {
            data.refreshPlaces(api)
            delay(2_000)
        } while (true)
    }
    LaunchedEffect(data, data.retryCount) {
        do {
            data.refreshNoise(api, NoisePeriod.DAY)
            delay(60_000)
        } while (true)
    }
    val showNight = showSound && data.noisePeriod == NoisePeriod.NIGHT
    LaunchedEffect(data, showNight, data.retryCount) {
        if (showNight) {
            do {
                data.refreshNoise(api, NoisePeriod.NIGHT)
                delay(60_000)
            } while (true)
        }
    }
    return data
}
