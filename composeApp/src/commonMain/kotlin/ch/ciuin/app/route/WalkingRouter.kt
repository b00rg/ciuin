package ch.ciuin.app.route

import ch.ciuin.shared.api.ContractJson
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal const val DEFAULT_WALKING_ROUTER_URL = "https://routing.openstreetmap.de/routed-foot"
private const val REQUEST_TIMEOUT_MILLIS = 4_000L
private const val REQUEST_INTERVAL_MILLIS = 1_000L
private const val SNAP_RADIUS_METERS = 50

internal data class WalkingRoute(val points: List<RoutePoint>, val distanceMeters: Double)

/** One instance per app process so requests share the service's rate limit. */
internal class WalkingRouter(baseUrl: String = DEFAULT_WALKING_ROUTER_URL, engine: HttpClientEngine? = null) {
    private val baseUrl = baseUrl.trim().trimEnd('/')
    private val http = if (engine == null) HttpClient { configure() } else HttpClient(engine) { configure() }
    private val requests = Mutex()
    private var lastRequestFinishedAt: TimeMark? = null
    private var lastRoute: CachedRoute? = null

    suspend fun findRoute(origin: RoutePoint, destination: RoutePoint): WalkingRoute? {
        if (baseUrl.isEmpty() || !origin.isValid || !destination.isValid) return null

        return withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
            requests.withLock {
                val cached = lastRoute
                if (cached != null && cached.origin == origin && cached.destination == destination) return@withLock cached.route

                lastRequestFinishedAt?.let { finished ->
                    delay((REQUEST_INTERVAL_MILLIS - finished.elapsedNow().inWholeMilliseconds).coerceAtLeast(0))
                }
                val route = try {
                    fetchRoute(origin, destination)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } finally {
                    lastRequestFinishedAt = TimeSource.Monotonic.markNow()
                }
                if (route != null) lastRoute = CachedRoute(origin, destination, route)
                route
            }
        }
    }

    private suspend fun fetchRoute(origin: RoutePoint, destination: RoutePoint): WalkingRoute? {
        val coordinates = "${origin.lon},${origin.lat};${destination.lon},${destination.lat}"
        val response = http.get("$baseUrl/route/v1/foot/$coordinates") {
            parameter("geometries", "geojson")
            parameter("overview", "full")
            parameter("steps", false)
            parameter("generate_hints", false)
            parameter("radiuses", "$SNAP_RADIUS_METERS;$SNAP_RADIUS_METERS")
        }
        if (!response.status.isSuccess()) return null

        val result = ContractJson.decodeFromString<OsrmResponse>(response.bodyAsText())
        if (result.code != "Ok") return null
        return result.routes.firstOrNull()?.toWalkingRoute(origin, destination)
    }

    fun close() = http.close()

    private fun HttpClientConfig<*>.configure() {
        followRedirects = false
        install(UserAgent) { agent = "Ciuin-SensoryMap/0.1 (https://github.com/b00rg/ciuin)" }
    }

    private data class CachedRoute(val origin: RoutePoint, val destination: RoutePoint, val route: WalkingRoute)
}

@Serializable
private data class OsrmResponse(val code: String, val routes: List<OsrmRoute> = emptyList())

@Serializable
private data class OsrmRoute(val distance: Double, val geometry: OsrmGeometry) {
    fun toWalkingRoute(origin: RoutePoint, destination: RoutePoint): WalkingRoute? {
        if (!distance.isFinite() || distance < 0 || geometry.type != "LineString" || geometry.coordinates.size < 2) return null
        val points = geometry.coordinates.map { coordinate ->
            if (coordinate.size < 2) return null
            val point = RoutePoint(lat = coordinate[1], lon = coordinate[0])
            if (!point.isValid) return null
            point
        }
        if (distanceMeters(origin, points.first()) > SNAP_RADIUS_METERS ||
            distanceMeters(destination, points.last()) > SNAP_RADIUS_METERS
        ) return null

        return WalkingRoute(points, distance)
    }
}

@Serializable
private data class OsrmGeometry(val type: String, val coordinates: List<List<Double>>)
