package ch.ciuin.app.ui.globe

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.ciuin.app.core.AppEnvironment
import ch.ciuin.app.globe.GlobePoint
import ch.ciuin.app.globe.GlobeSpike
import ch.ciuin.app.globe.LAUSANNE_CENTRE
import ch.ciuin.app.globe.globeSpikes
import ch.ciuin.app.globe.wrapLongitude
import ch.ciuin.app.ui.components.CiuinTopBar
import ch.ciuin.app.ui.components.LivePill
import ch.ciuin.app.ui.components.PrimaryButton
import ch.ciuin.app.ui.components.StatTile
import ch.ciuin.app.ui.components.TagChip
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinMotion
import ch.ciuin.app.ui.theme.CiuinType
import ch.ciuin.app.ui.theme.LocalReduceMotion
import ch.ciuin.shared.api.FallbackPlacesApi
import ch.ciuin.shared.api.Stats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GlobeOpening(environment: AppEnvironment, onEnterMap: () -> Unit, modifier: Modifier = Modifier) {
    val api = environment.api
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val enterMap by rememberUpdatedState(onEnterMap)
    var centre by remember { mutableStateOf(GlobePoint(40.0, 14.0)) }
    var flying by remember { mutableStateOf(false) }
    val leave = remember { Animatable(0f) }
    var totals by remember(api) { mutableStateOf<Stats?>(null) }
    var spikes by remember(api) { mutableStateOf<List<GlobeSpike>>(emptyList()) }
    var fetchingTotals by remember(api) { mutableStateOf(true) }
    var fetchingCoverage by remember(api) { mutableStateOf(true) }
    val online = (api as? FallbackPlacesApi)?.online?.collectAsState()?.value ?: true
    LaunchedEffect(api) {
        launch {
            totals = requestGlobeData { api.stats() }
            fetchingTotals = false
        }
        launch {
            spikes = requestGlobeData { globeSpikes(api.coverage()) }.orEmpty()
            fetchingCoverage = false
        }
    }

    Column(
        modifier.fillMaxSize().graphicsLayer { alpha = 1f - leave.value }.background(CiuinColors.Ground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        CiuinTopBar(wordmark = true, trailing = {
            when {
                !online -> TagChip("Offline")
                fetchingTotals || fetchingCoverage -> TagChip("Loading")
                totals != null -> LivePill()
                else -> TagChip("Offline")
            }
        })
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DottedGlobe(centre, spikes, onRotate = { centre = it }, rotationEnabled = !flying)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Every spike is a place someone measured.",
                    Modifier.semantics { heading() },
                    style = CiuinType.ScreenTitle,
                )
                Text(
                    "Drag to spin the globe. Lausanne has the most detail so far.",
                    style = CiuinType.Body,
                    color = CiuinColors.SecondaryText,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(formattedCount(totals?.placesMapped), "places", Modifier.weight(1f))
                StatTile(formattedCount(totals?.measurementsTaken), "readings", Modifier.weight(1f))
                StatTile(formattedCount(totals?.sensoryFriendlyTags), "tags", Modifier.weight(1f))
            }
        }
        PrimaryButton(
            "Explore near you",
            modifier = Modifier.fillMaxWidth(),
            enabled = !flying,
            onClick = {
                if (!flying) {
                    flying = true
                    scope.launch {
                        if (reduceMotion) {
                            leave.animateTo(1f, CiuinMotion.reducedFade())
                        } else {
                            val from = centre
                            val longitudeDelta = wrapLongitude(LAUSANNE_CENTRE.lon - from.lon)
                            animate(0f, 1f, animationSpec = tween(450, easing = CiuinMotion.EaseInOut)) { progress, _ ->
                                centre = GlobePoint(
                                    from.lat + (LAUSANNE_CENTRE.lat - from.lat) * progress,
                                    wrapLongitude(from.lon + longitudeDelta * progress),
                                )
                            }
                        }
                        enterMap()
                    }
                }
            },
        )
    }
}

private fun formattedCount(value: Int?): String = value?.toString()?.reversed()?.chunked(3)
    ?.joinToString(",")?.reversed() ?: "—"

private suspend fun <T> requestGlobeData(request: suspend () -> T): T? = try {
    request()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
