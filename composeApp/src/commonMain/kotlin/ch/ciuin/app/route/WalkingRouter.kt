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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal const val DEFAULT_WALKING_ROUTER_URL = "https://routing.openstreetmap.de/routed-foot"
private const val REQUEST_TIMEOUT_MILLIS = 4_000L
private const val REQUEST_INTERVAL_MILLIS = 1_000L
private const val SNAP_RADIUS_METERS = 50

data class WalkingRoute(
    val points: List<RoutePoint>,
    val distanceMeters: Double,
    val durationSeconds: Double? = null,
    val steps: List<RouteStep> = emptyList(),
)

enum class ManeuverKind {
    DEPART, TURN_LEFT, TURN_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT, U_TURN,
    CONTINUE, ARRIVE_LEFT, ARRIVE_RIGHT, ARRIVE_AHEAD,
}

enum class CompassHeading { NORTH, NORTHEAST, EAST, SOUTHEAST, SOUTH, SOUTHWEST, WEST, NORTHWEST }

data class RouteStep(
    val kind: ManeuverKind,
    val streetName: String?,
    val heading: CompassHeading?,
    val distanceMeters: Double,
)

/** Departures and arrivals can be meaningful at zero metres, as can turns at the same intersection. */
private fun mergeTrivialSteps(steps: List<RouteStep>): List<RouteStep> = buildList {
    for (step in steps) {
        val previous = lastOrNull()
        if (step.kind == ManeuverKind.CONTINUE && previous?.kind == ManeuverKind.DEPART &&
            previous.distanceMeters == 0.0 && (previous.streetName == null || previous.streetName == step.streetName)
        ) {
            set(lastIndex, previous.copy(streetName = step.streetName, distanceMeters = step.distanceMeters))
        } else if (step.kind != ManeuverKind.CONTINUE || step.distanceMeters > 0) {
            add(step)
        }
    }
}

/** English fallback copy for the route's typed maneuvers. */
fun routeStepPhrase(step: RouteStep, destination: String): String {
    val street = step.streetName
    val onto = street?.let { " onto $it" }.orEmpty()
    return when (step.kind) {
        ManeuverKind.DEPART -> "Head" + step.heading?.let { " ${it.name.lowercase()}" }.orEmpty() +
            street?.let { " on $it" }.orEmpty()
        ManeuverKind.TURN_LEFT -> "Turn left$onto"
        ManeuverKind.TURN_RIGHT -> "Turn right$onto"
        ManeuverKind.SLIGHT_LEFT -> "Bear left$onto"
        ManeuverKind.SLIGHT_RIGHT -> "Bear right$onto"
        ManeuverKind.SHARP_LEFT -> "Turn sharply left$onto"
        ManeuverKind.SHARP_RIGHT -> "Turn sharply right$onto"
        ManeuverKind.U_TURN -> "Make a U-turn$onto"
        ManeuverKind.CONTINUE -> street?.let { "Continue on $it" } ?: "Continue straight"
        ManeuverKind.ARRIVE_LEFT -> "$destination is on your left"
        ManeuverKind.ARRIVE_RIGHT -> "$destination is on your right"
        ManeuverKind.ARRIVE_AHEAD -> "$destination is ahead"
    }
}

/** One instance per app process so requests share the service's rate limit. */
class WalkingRouter(baseUrl: String = DEFAULT_WALKING_ROUTER_URL, engine: HttpClientEngine? = null) {
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
            parameter("steps", true)
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
private data class OsrmRoute(
    val distance: Double,
    val geometry: OsrmGeometry,
    val duration: JsonElement? = null,
    val legs: JsonElement? = null,
) {
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

        // Optional step fields can evolve without invalidating a usable route geometry.
        val steps = (legs as? JsonArray).orEmpty().flatMap { leg ->
            val entries = (leg as? JsonObject)?.get("steps") as? JsonArray
            entries.orEmpty().mapNotNull { (it as? JsonObject)?.toRouteStep() }
        }
        return WalkingRoute(points, distance, duration.nonNegativeNumber(), mergeTrivialSteps(steps))
    }
}

@Serializable
private data class OsrmGeometry(val type: String, val coordinates: List<List<Double>>)


private fun JsonElement?.nonNegativeNumber(): Double? =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.toRouteStep(): RouteStep? {
    val distance = get("distance").nonNegativeNumber() ?: return null
    val maneuver = get("maneuver") as? JsonObject ?: return null
    val type = maneuver.text("type") ?: return null
    val modifier = maneuver.text("modifier")
    val kind = when (type) {
        "depart" -> ManeuverKind.DEPART
        "arrive" -> when (modifier) {
            "left" -> ManeuverKind.ARRIVE_LEFT
            "right" -> ManeuverKind.ARRIVE_RIGHT
            else -> ManeuverKind.ARRIVE_AHEAD
        }
        // OSRM uses the same direction modifiers for turns, forks and street-name changes.
        else -> when (modifier) {
            "left" -> ManeuverKind.TURN_LEFT
            "right" -> ManeuverKind.TURN_RIGHT
            "slight left" -> ManeuverKind.SLIGHT_LEFT
            "slight right" -> ManeuverKind.SLIGHT_RIGHT
            "sharp left" -> ManeuverKind.SHARP_LEFT
            "sharp right" -> ManeuverKind.SHARP_RIGHT
            "uturn" -> ManeuverKind.U_TURN
            "straight" -> ManeuverKind.CONTINUE
            null -> if (type == "continue") ManeuverKind.CONTINUE else return null
            else -> return null
        }
    }
    val bearing = (maneuver["bearing_after"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    val heading = if (kind == ManeuverKind.DEPART && bearing != null && bearing in 0..360) {
        CompassHeading.entries[((bearing + 22.5) / 45).toInt() % 8]
    } else null
    return RouteStep(kind, text("name"), heading, distance)
}
