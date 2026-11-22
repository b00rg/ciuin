package ch.ciuin.shared.api

import ch.ciuin.shared.model.Coverage
import ch.ciuin.shared.model.Measurement
import ch.ciuin.shared.model.MeasurementResult
import ch.ciuin.shared.model.Place
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.JsonObject

/**
 * [PlacesApi] against the FastAPI backend (backend/main.py).
 *
 * @param baseUrl e.g. "http://localhost:8000" (desktop), "http://10.0.2.2:8000" (Android emulator ->
 *   host machine), or the Cloudflare tunnel URL for real phones.
 * @param engine leave null to use the platform engine (OkHttp on Android, CIO on desktop);
 *   tests pass a MockEngine.
 *
 * Non-2xx responses throw (ClientRequestException / ServerResponseException); so do network errors.
 */
class KtorPlacesApi(
    baseUrl: String,
    engine: HttpClientEngine? = null,
) : PlacesApi {
    val baseUrl: String = baseUrl.trimEnd('/')

    private val http: HttpClient = if (engine == null) HttpClient { configure() } else HttpClient(engine) { configure() }

    private fun HttpClientConfig<*>.configure() {
        expectSuccess = true
        install(ContentNegotiation) { json(ContractJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 15_000
        }
    }

    override suspend fun places(bbox: BBox, at: String?): List<Place> =
        http.get("$baseUrl/places") {
            parameter("bbox", bbox.toQuery())
            at?.let { parameter("at", it) }
        }.body()

    override suspend fun postMeasurement(m: Measurement): MeasurementResult =
        http.post("$baseUrl/measurements") {
            contentType(ContentType.Application.Json)
            setBody(m)
        }.body()

    override suspend fun heatmap(bbox: BBox, dim: String, period: NoisePeriod?): JsonObject =
        http.get("$baseUrl/heatmap") {
            parameter("bbox", bbox.toQuery())
            parameter("dim", dim)
            period?.let { parameter("period", it.queryValue) }
        }.body()

    override suspend fun nearby(lat: Double, lon: Double, radiusM: Int, openOnly: Boolean, at: String?): List<Place> =
        http.get("$baseUrl/places/nearby") {
            parameter("lat", lat)
            parameter("lon", lon)
            parameter("radius", radiusM)
            if (openOnly) parameter("open_only", true)
            at?.let { parameter("at", it) }
        }.body()

    override suspend fun stats(): Stats = http.get("$baseUrl/stats").body()

    override suspend fun coverage(resDeg: Double): Coverage =
        http.get("$baseUrl/coverage") { parameter("res_deg", resDeg) }.body()

    fun close() = http.close()
}
