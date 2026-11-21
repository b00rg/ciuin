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
            assertEquals("false", request.url.parameters["steps"])
            assertEquals("false", request.url.parameters["generate_hints"])
            assertEquals("50;50", request.url.parameters["radiuses"])
            assertEquals("Ciuin-SensoryMap/0.1 (https://github.com/b00rg/ciuin)", request.headers[HttpHeaders.UserAgent])
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
    ): String = """{"code":"Ok","routes":[{"distance":$distance,"geometry":{"type":"$type","coordinates":$coordinates}}]}"""
}
