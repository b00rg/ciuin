package ch.ciuin.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.ciuin.app.route.QuietPlaceRoute
import ch.ciuin.app.route.RoutePoint
import ch.ciuin.app.route.WalkingRoute
import ch.ciuin.app.route.WalkingRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class RouteController(
    private val scope: CoroutineScope,
    private val fetch: (suspend (RoutePoint, RoutePoint) -> WalkingRoute?)?,
) {
    constructor(scope: CoroutineScope, router: WalkingRouter?) : this(scope, router?.let { it::findRoute })

    var active by mutableStateOf<QuietPlaceRoute?>(null)
        private set
    var walkingRoute by mutableStateOf<WalkingRoute?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var requestCount by mutableIntStateOf(0)
        private set

    private var request: Job? = null

    fun start(route: QuietPlaceRoute) {
        request?.cancel()
        active = route
        walkingRoute = null
        val generation = ++requestCount
        loading = fetch != null
        request = fetch?.let { find ->
            scope.launch {
                val result = try {
                    find(route.origin, route.destination)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (generation == requestCount && active != null) {
                    walkingRoute = result
                    loading = false
                }
            }
        }
    }

    fun end() {
        request?.cancel()
        request = null
        active = null
        walkingRoute = null
        loading = false
    }
}
