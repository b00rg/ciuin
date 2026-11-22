package ch.ciuin.app.route

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class WalkingRouterTest {
    private val origin = RoutePoint(46.5235, 6.6326)
    private val destination = RoutePoint(46.5225, 6.6345)

    @Test
    fun requestsPedestrianGeometryAndReusesTheLastSuccessfulRoute() = runBlocking<Unit> {
        val requests = mutableListOf<HttpRequestData>()
        withRouter(MockEngine { request ->
            requests += request
            respond(routeResponse())
        }) { router ->
            val route = WalkingRoute(listOf(origin, RoutePoint(46.5238, 6.634), destination), 318.5)
            assertEquals(route, router.findRoute(origin, destination))
            assertEquals(route, router.findRoute(origin, destination))

            val request = requests.single()
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/routed-foot/route/v1/foot/6.6326,46.5235;6.6345,46.5225", request.url.encodedPath)
            assertEquals("geojson", request.url.parameters["geometries"])
            assertEquals("full", request.url.parameters["overview"])
            assertEquals("true", request.url.parameters["steps"])
            assertEquals("false", request.url.parameters["generate_hints"])
            assertEquals("50;50", request.url.parameters["radiuses"])
            assertEquals("Ciuin-SensoryMap/0.1 (https://github.com/b00rg/ciuin)", request.headers[HttpHeaders.UserAgent])
        }
    }

    @Test
    fun readsRecordedOsrmWalkingSteps() = runBlocking<Unit> {
        // Recorded from routing.openstreetmap.de/routed-foot for the coordinates above.
        val recorded = checkNotNull(WalkingRouterTest::class.java.getResource("osrm-lausanne-walk.json")).readText()
        withRouter(MockEngine { respond(recorded) }) { router ->
            val route = assertNotNull(router.findRoute(origin, destination))
            assertEquals(427.8, route.distanceMeters)
            assertEquals(341.8, route.durationSeconds)
            assertEquals(14, route.steps.size)
            assertEquals(RouteStep(ManeuverKind.DEPART, "Place de la Riponne", CompassHeading.SOUTHWEST, 3.5), route.steps[0])
            assertEquals(RouteStep(ManeuverKind.TURN_RIGHT, null, null, 10.2), route.steps[1])
            assertEquals(RouteStep(ManeuverKind.TURN_LEFT, "Escaliers Marc-Louis-Arlaud", null, 52.1), route.steps[4])
            assertEquals(ManeuverKind.SLIGHT_LEFT, route.steps[10].kind)
            assertEquals(RouteStep(ManeuverKind.ARRIVE_RIGHT, "Place de la Cathédrale", null, 0.0), route.steps.last())
        }
    }

    @Test
    fun skipsMalformedStepsAndMergesZeroDistanceContinuations() = runBlocking<Unit> {
        val legs = """[
            {"steps":false},
            {"steps":[null, "bad", {"distance":3}, {"distance":-5,"maneuver":{"type":"turn","modifier":"left"}},
                {"distance":1e400,"maneuver":{"type":"turn","modifier":"left"}},
                {"distance":"5","maneuver":{"type":"turn","modifier":"left"}},
                {"distance":0,"maneuver":{"type":"depart","bearing_after":272}},
                {"distance":300,"name":"Rue de Genève","maneuver":{"type":"continue","modifier":"straight"}},
                {"distance":0,"maneuver":{"type":"continue","modifier":"straight"}},
                {"distance":0,"name":"Avenue de la Gare","maneuver":{"type":"turn","modifier":"left"}},
                {"distance":18.5,"maneuver":{"type":"turn","modifier":"sharp right"}},
                {"distance":0,"maneuver":{"type":"arrive","modifier":"right"}}
            ]}]"""
        withRouter(MockEngine { respond(routeResponse(extraFields = ""","duration":{},"legs":$legs""")) }) { router ->
            val route = assertNotNull(router.findRoute(origin, destination))
            assertNull(route.durationSeconds)
            assertEquals(
                listOf(
                    RouteStep(ManeuverKind.DEPART, "Rue de Genève", CompassHeading.WEST, 300.0),
                    RouteStep(ManeuverKind.TURN_LEFT, "Avenue de la Gare", null, 0.0),
                    RouteStep(ManeuverKind.SHARP_RIGHT, null, null, 18.5),
                    RouteStep(ManeuverKind.ARRIVE_RIGHT, null, null, 0.0),
                ),
                route.steps,
            )
            assertEquals(
                listOf("Head west on Rue de Genève", "Turn left onto Avenue de la Gare", "Turn sharply right",
                    "Palais de Rumine is on your right"),
                route.steps.map { routeStepPhrase(it, "Palais de Rumine") },
            )
        }
    }

    @Test
    fun worksWithDurationAndNoSteps() = runBlocking<Unit> {
        withRouter(MockEngine { respond(routeResponse(extraFields = ""","duration":240.0,"legs":[{}]""")) }) { router ->
            val route = assertNotNull(router.findRoute(origin, destination))
            assertEquals(240.0, route.durationSeconds)
            assertTrue(route.steps.isEmpty())
        }
    }

    @Test
    fun fallsBackOnMissingRoutesAndRequestFailures() = runBlocking<Unit> {
        val responses = listOf(HttpStatusCode.OK to """{"code":"NoRoute"}""", HttpStatusCode.ServiceUnavailable to "Unavailable")
        for ((status, body) in responses) {
            withRouter(MockEngine { respond(body, status) }) { router ->
                assertNull(router.findRoute(origin, destination))
            }
        }
        withRouter(MockEngine { throw IOException("Offline") }) { router ->
            assertNull(router.findRoute(origin, destination))
        }
    }

    @Test
    fun fallsBackOnInvalidGeometryOrDistance() = runBlocking<Unit> {
        val responses = listOf(
            "not JSON",
            routeResponse(type = "MultiPoint"),
            routeResponse(coordinates = "[[6.6326,46.5235],[6.6345,91]]"),
            routeResponse(coordinates = "[[6.6326,46.5235],[6.6345]]"),
            routeResponse(coordinates = "[[6.6326,46.5235]]"),
            routeResponse(coordinates = "[[6.6326,46.5235],[6.6345,46.5310]]"),
            routeResponse(distance = "-1"),
            routeResponse(distance = "1e400"),
        )
        for (body in responses) {
            withRouter(MockEngine { respond(body) }) { router ->
                assertNull(router.findRoute(origin, destination), body)
            }
        }
    }

    @Test
    fun aFailedLookupCanBeRetriedAfterTheRequestInterval() = runBlocking<Unit> {
        val start = TimeSource.Monotonic.markNow()
        val requestsAt = mutableListOf<Long>()
        withRouter(MockEngine {
            requestsAt += start.elapsedNow().inWholeMilliseconds
            if (requestsAt.size == 1) respond("Unavailable", HttpStatusCode.ServiceUnavailable) else respond(routeResponse())
        }) { router ->
            assertNull(router.findRoute(origin, destination))
            assertEquals(318.5, router.findRoute(origin, destination)?.distanceMeters)
            assertTrue(requestsAt[1] - requestsAt[0] >= 995)
        }
    }

    @Test
    fun propagatesCallerCancellation() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        withRouter(MockEngine {
            started.complete(Unit)
            awaitCancellation()
        }) { router ->
            val pending = async { router.findRoute(origin, destination) }
            started.await()
            pending.cancel()
            assertFailsWith<CancellationException> { pending.await() }
        }
    }

    private inline fun <T> withRouter(engine: MockEngine, test: (WalkingRouter) -> T): T {
        val router = WalkingRouter("https://router.example/routed-foot/", engine)
        try {
            return test(router)
        } finally {
            router.close()
            engine.close()
        }
    }

    private fun routeResponse(
        coordinates: String = "[[6.6326,46.5235],[6.634,46.5238],[6.6345,46.5225]]",
        type: String = "LineString",
        distance: String = "318.5",
        extraFields: String = "",
    ): String = """{"code":"Ok","routes":[{"distance":$distance,"geometry":{"type":"$type","coordinates":$coordinates}$extraFields}]}"""
}
