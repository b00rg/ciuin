package ch.ciuin.shared.api

import ch.ciuin.shared.model.Coverage
import ch.ciuin.shared.model.Measurement
import ch.ciuin.shared.model.Place
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.JsonObject

/**
 * Real backend client (todo.md §0 API). Talks to Siddhi's FastAPI server at [baseUrl],
 * e.g. "http://192.168.43.10:8000". Throws on network errors / non-2xx so callers
 * (see [FallbackPlacesApi]) can decide what to do.
 */
class KtorPlacesApi(
    baseUrl: String,
    /** null = platform default (OkHttp on Android, CIO on desktop). Tests pass a MockEngine. */
    engine: HttpClientEngine? = null,
) : PlacesApi, AutoCloseable {

    private val configure: HttpClientConfig<*>.() -> Unit = {
        expectSuccess = true
        install(ContentNegotiation) { json(ContractJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            requestTimeoutMillis = 8_000
        }
        defaultRequest { url(baseUrl.trimEnd('/') + "/") }
    }

    private val client: HttpClient = if (engine != null) HttpClient(engine, configure) else HttpClient(configure)

    override suspend fun places(bbox: BBox): List<Place> =
        client.get("places") { parameter("bbox", bbox.toQuery()) }.body()

    override suspend fun postMeasurement(m: Measurement) {
        client.post("measurements") {
            contentType(ContentType.Application.Json)
            setBody(m)
        }
    }

    override suspend fun heatmap(bbox: BBox, dim: String): JsonObject =
        client.get("heatmap") {
            parameter("bbox", bbox.toQuery())
            parameter("dim", dim)
        }.body()

    override suspend fun nearby(lat: Double, lon: Double, radiusM: Int): List<Place> =
        client.get("places/nearby") {
            parameter("lat", lat)
            parameter("lon", lon)
            parameter("radius", radiusM)
        }.body()

    override suspend fun stats(): Stats = client.get("stats").body()

    override suspend fun coverage(): Coverage = client.get("coverage").body()

    override fun close() = client.close()
}
