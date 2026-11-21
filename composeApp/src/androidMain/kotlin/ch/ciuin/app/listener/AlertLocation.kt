package ch.ciuin.app.listener

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import ch.ciuin.app.route.RoutePoint
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

internal object AlertLocation {
    private const val FIX_TIMEOUT_MILLIS = 2_000L
    private const val MAX_FIX_AGE_NANOS = 20_000_000_000L
    private const val MAX_ACCURACY_METERS = 100f
    private val activeProviders = setOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    fun canLocate(context: Context): Boolean = enabledProviders(context).isNotEmpty()

    @SuppressLint("MissingPermission")
    suspend fun currentPoint(context: Context): RoutePoint? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = enabledProviders(context)
        if (providers.isEmpty()) return null

        // Another app may already have obtained a recent fix without starting our sensors.
        val cached = (providers + LocationManager.PASSIVE_PROVIDER).mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.filter(::isUsable).maxByOrNull { it.elapsedRealtimeNanos }
        if (cached != null) return RoutePoint(cached.latitude, cached.longitude)

        val executor = ContextCompat.getMainExecutor(context)
        val fresh = withTimeoutOrNull(FIX_TIMEOUT_MILLIS) {
            providers.map { provider -> flow { emit(requestFix(manager, provider, executor)) } }
                .merge()
                .filterNotNull()
                .firstOrNull(::isUsable)
        }
        return fresh?.let { RoutePoint(it.latitude, it.longitude) }
    }

    private fun enabledProviders(context: Context): List<String> {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val manager = context.getSystemService(LocationManager::class.java) ?: return emptyList()
        if (!LocationManagerCompat.isLocationEnabled(manager)) return emptyList()
        return manager.getProviders(true).filter { it in activeProviders }
    }

    private fun isUsable(location: Location): Boolean {
        val ageNanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        return ageNanos in 0L..MAX_FIX_AGE_NANOS &&
            location.hasAccuracy() && location.accuracy in 0f..MAX_ACCURACY_METERS &&
            RoutePoint(location.latitude, location.longitude).isValid
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestFix(manager: LocationManager, provider: String, executor: Executor): Location? =
        suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            continuation.invokeOnCancellation { cancellation.cancel() }
            if (!continuation.isActive) return@suspendCancellableCoroutine

            try {
                LocationManagerCompat.getCurrentLocation(manager, provider, cancellation, executor) { location ->
                    continuation.resume(location)
                }
            } catch (_: RuntimeException) {
                continuation.resume(null)
            }
        }
}
