package ch.ciuin.app.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.ciuin.app.route.RoutePoint

internal class AndroidLocationSource(private val context: Context) : LocationSource, LocationListener {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var requested = false
    private var foreground = false
    private var active = false
    private var lastFix = Long.MIN_VALUE
    override var current by mutableStateOf<RoutePoint?>(null)
        private set

    override fun start() {
        requested = true
        refresh()
    }

    override fun stop() {
        requested = false
        refresh()
    }

    fun setForeground(value: Boolean) {
        foreground = value
        refresh()
    }

    @SuppressLint("MissingPermission")
    fun refresh() {
        val manager = manager ?: return
        val fine = has(Manifest.permission.ACCESS_FINE_LOCATION)
        val allowed = fine || has(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!requested || !foreground || !allowed) {
            if (active) manager.removeUpdates(this)
            active = false
            lastFix = Long.MIN_VALUE
            current = null
            return
        }
        if (active) return
        val providers = manager.getProviders(true).filter {
            it == LocationManager.NETWORK_PROVIDER || it == LocationManager.FUSED_PROVIDER ||
                (fine && it == LocationManager.GPS_PROVIDER)
        }
        providers.forEach { provider ->
            try {
                manager.requestLocationUpdates(provider, 1_000L, 0f, this, Looper.getMainLooper())
                active = true
                val cached = manager.getLastKnownLocation(provider)
                if (cached != null && SystemClock.elapsedRealtimeNanos() - cached.elapsedRealtimeNanos in 0..20_000_000_000L) {
                    onLocationChanged(cached)
                }
            } catch (_: SecurityException) {
                // The permission may have changed after the check.
            } catch (_: IllegalArgumentException) {
                // A provider can disappear between enumeration and registration.
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        if (!active || location.elapsedRealtimeNanos < lastFix) return
        val point = RoutePoint(location.latitude, location.longitude)
        if (point.isValid) {
            lastFix = location.elapsedRealtimeNanos
            current = point
        }
    }

    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) {
        if (manager?.getProviders(true)?.none { it != LocationManager.PASSIVE_PROVIDER } == true) current = null
    }
    @Deprecated("Required on Android 29 and earlier")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private fun has(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
