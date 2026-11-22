package ch.ciuin.app.ui.globe

import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.LinearEasing
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
import ch.ciuin.app.core.AppPermission
import ch.ciuin.app.core.PermissionStatus
import ch.ciuin.app.globe.GlobePoint
import ch.ciuin.app.globe.GlobeSpike
import ch.ciuin.app.globe.LAUSANNE_CENTRE
import ch.ciuin.app.globe.globeSpikes
import ch.ciuin.app.globe.wrapLongitude
import ch.ciuin.app.route.RoutePoint
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
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

@Composable
/**
 * [onEnterMap] gets where the user is when "Explore near you" found them, or null (no permission, no fix in
 * time), in which case the map keeps its default view of the mapped area.
 */
internal fun GlobeOpening(
    environment: AppEnvironment,
    onEnterMap: (RoutePoint?) -> Unit,
    modifier: Modifier = Modifier,
    quietPlaces: Int? = null,
) {
    val api = environment.api
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val enterMap by rememberUpdatedState(onEnterMap)
    var centre by remember { mutableStateOf(GlobePoint(40.0, 14.0)) }
    var flying by remember { mutableStateOf(false) }
    val leave = remember { Animatable(0f) }
    // Fly-in: the globe grows (we dive into it) while the text and buttons fade, then the map takes over.
    var globeScale by remember { mutableStateOf(1f) }
    var chromeAlpha by remember { mutableStateOf(1f) }
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
        CiuinTopBar(wordmark = true, wordmarkMeaning = true, trailing = {
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
            Box(Modifier.zIndex(1f).graphicsLayer { scaleX = globeScale; scaleY = globeScale }) {
                DottedGlobe(centre, spikes, onRotate = { centre = it }, rotationEnabled = !flying)
            }
            Column(Modifier.graphicsLayer { alpha = chromeAlpha }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Row(Modifier.graphicsLayer { alpha = chromeAlpha }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(formattedCount(totals?.placesMapped), "places", Modifier.weight(1f))
                StatTile(formattedCount(totals?.measurementsTaken), "readings", Modifier.weight(1f))
                StatTile(formattedCount(quietPlaces), "quiet spots", Modifier.weight(1f))
            }
        }
        PrimaryButton(
            "Explore near you",
            modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = chromeAlpha },
            enabled = !flying,
            onClick = {
                if (!flying) {
                    flying = true
                    scope.launch {
                        // The button is the user asking to see where they are, so it may ask for location.
                        val here = if (environment.permissions.request(AppPermission.LOCATION) == PermissionStatus.GRANTED) {
                            withTimeoutOrNull(LOCATION_WAIT_MS) {
                                snapshotFlow { environment.location.current }.filterNotNull().first()
                            }
                        } else null
                        val target = here?.let { GlobePoint(it.lat, it.lon) } ?: LAUSANNE_CENTRE
                        if (reduceMotion) {
                            leave.animateTo(1f, CiuinMotion.reducedFade())
                        } else {
                            // Like a camera diving in: turn to the target while zooming into the globe, faster
                            // and faster; the text goes first, the globe fades in the last third, and the map
                            // (opening zoomed out over the same spot) carries the zoom on to street level.
                            val from = centre
                            val longitudeDelta = wrapLongitude(target.lon - from.lon)
                            animate(0f, 1f, animationSpec = tween(GLOBE_DIVE_MS, easing = LinearEasing)) { t, _ ->
                                val turn = CiuinMotion.EaseInOut.transform((t / 0.7f).coerceAtMost(1f))
                                centre = GlobePoint(
                                    from.lat + (target.lat - from.lat) * turn,
                                    wrapLongitude(from.lon + longitudeDelta * turn),
                                )
                                globeScale = 1f + (GLOBE_DIVE_SCALE - 1f) * t * t * t
                                chromeAlpha = (1f - t / 0.35f).coerceIn(0f, 1f)
                                launch { leave.snapTo(((t - 0.65f) / 0.35f).coerceIn(0f, 1f)) }
                            }
                        }
                        enterMap(here)
                    }
                }
            },
        )
    }
}

/** The globe part of the fly-in: how long, and how far the globe grows by the end. */
private const val GLOBE_DIVE_MS = 1_400
private const val GLOBE_DIVE_SCALE = 4.5f

/** How long "Explore near you" waits for a first position fix before flying to the mapped area instead. */
private const val LOCATION_WAIT_MS = 4_000L

private fun formattedCount(value: Int?): String = value?.toString()?.reversed()?.chunked(3)
    ?.joinToString(",")?.reversed() ?: "—"

private suspend fun <T> requestGlobeData(request: suspend () -> T): T? = try {
    request()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
