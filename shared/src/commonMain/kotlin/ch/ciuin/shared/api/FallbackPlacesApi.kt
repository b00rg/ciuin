package ch.ciuin.shared.api

import ch.ciuin.shared.model.Coverage
import ch.ciuin.shared.model.Measurement
import ch.ciuin.shared.model.MeasurementResult
import ch.ciuin.shared.model.Place
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

data class PlaceRead<out T>(val data: T, val fromPrimary: Boolean)

/**
 * Uses [primary] (the real backend) and falls back to [fallback] (bundled / fake data) when it's unreachable,
 * so the app keeps working on a bad network. [online] tells the UI which one answered last.
 *
 * Measurements taken while offline are kept in memory and re-sent on the next successful call.
 * (In memory only: they're lost if the app is killed.) Measurements the backend *rejects* (4xx, e.g. the
 * 422 privacy guard) are not queued: the error is thrown so the bug gets noticed.
 */
class FallbackPlacesApi(
    val primary: PlacesApi,
    private val fallback: PlacesApi,
) : PlacesApi {

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val pendingLock = Mutex()
    private val pending = ArrayDeque<Measurement>()

    /** Measurements waiting to be uploaded. */
    suspend fun pendingCount(): Int = pendingLock.withLock { pending.size }

    private suspend fun <T> readWithFallback(call: suspend PlacesApi.() -> T): PlaceRead<T> =
        try {
            val result = primary.call()
            _online.value = true
            flushPending()
            PlaceRead(result, fromPrimary = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _online.value = false
            PlaceRead(fallback.call(), fromPrimary = false)
        }

    private suspend fun <T> withFallback(call: suspend PlacesApi.() -> T): T = readWithFallback(call).data

    private suspend fun flushPending(alreadyStored: Measurement? = null) = pendingLock.withLock {
        if (alreadyStored?.measurementId != null) pending.remove(alreadyStored)
        while (pending.isNotEmpty()) {
            try {
                primary.postMeasurement(pending.first())
                pending.removeFirst()
            } catch (e: CancellationException) {
                throw e
            } catch (_: ClientRequestException) {
                pending.removeFirst() // rejected (4xx): retrying won't help
            } catch (_: Exception) {
                return@withLock // still failing; try again next time
            }
        }
    }

    suspend fun readPlaces(bbox: BBox, at: String? = null): PlaceRead<List<Place>> = readWithFallback { places(bbox, at) }

    override suspend fun places(bbox: BBox, at: String?): List<Place> = readPlaces(bbox, at).data

    /** Returns `stored = false` when the measurement was queued for later instead of uploaded. */
    override suspend fun postMeasurement(m: Measurement): MeasurementResult =
        try {
            val result = primary.postMeasurement(m)
            _online.value = true
            flushPending(m.takeIf { result.stored })
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: ClientRequestException) {
            _online.value = true // the server answered; it just didn't like the request
            throw e
        } catch (_: Exception) {
            _online.value = false
            pendingLock.withLock {
                if (m.measurementId == null || m !in pending) pending.addLast(m)
            }
            MeasurementResult(stored = false)
        }

    suspend fun readHeatmap(bbox: BBox, dim: String, period: NoisePeriod?): PlaceRead<JsonObject> =
        readWithFallback { heatmap(bbox, dim, period) }

    override suspend fun heatmap(bbox: BBox, dim: String, period: NoisePeriod?): JsonObject = readHeatmap(bbox, dim, period).data

    override suspend fun nearby(lat: Double, lon: Double, radiusM: Int, openOnly: Boolean, at: String?): List<Place> =
        withFallback { nearby(lat, lon, radiusM, openOnly, at) }

    override suspend fun stats(): Stats = withFallback { stats() }

    override suspend fun coverage(resDeg: Double): Coverage = withFallback { coverage(resDeg) }
}
