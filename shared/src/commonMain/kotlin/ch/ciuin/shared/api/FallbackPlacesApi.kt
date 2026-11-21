package ch.ciuin.shared.api

import ch.ciuin.shared.model.Coverage
import ch.ciuin.shared.model.Measurement
import ch.ciuin.shared.model.Place
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * Uses [primary] (the real backend) and falls back to [fallback] (bundled / fake data) when it's unreachable,
 * so the app keeps working on a bad network. [online] tells the UI which one answered last.
 *
 * Measurements taken while offline are kept in memory and re-sent on the next successful call.
 * (In memory only: they're lost if the app is killed.)
 */
class FallbackPlacesApi(
    private val primary: PlacesApi,
    private val fallback: PlacesApi,
) : PlacesApi {

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val pendingLock = Mutex()
    private val pending = ArrayDeque<Measurement>()

    /** Measurements waiting to be uploaded. */
    suspend fun pendingCount(): Int = pendingLock.withLock { pending.size }

    private suspend fun <T> withFallback(call: suspend PlacesApi.() -> T): T =
        try {
            val result = primary.call()
            _online.value = true
            flushPending()
            result
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _online.value = false
            fallback.call()
        }

    private suspend fun flushPending() = pendingLock.withLock {
        while (pending.isNotEmpty()) {
            try {
                primary.postMeasurement(pending.first())
                pending.removeFirst()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withLock // still failing; try again next time
            }
        }
    }

    override suspend fun places(bbox: BBox): List<Place> = withFallback { places(bbox) }

    override suspend fun postMeasurement(m: Measurement) {
        try {
            primary.postMeasurement(m)
            _online.value = true
            flushPending()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _online.value = false
            pendingLock.withLock { pending.addLast(m) }
        }
    }

    override suspend fun heatmap(bbox: BBox, dim: String): JsonObject = withFallback { heatmap(bbox, dim) }

    override suspend fun nearby(lat: Double, lon: Double, radiusM: Int): List<Place> =
        withFallback { nearby(lat, lon, radiusM) }

    override suspend fun stats(): Stats = withFallback { stats() }

    override suspend fun coverage(): Coverage = withFallback { coverage() }
}
