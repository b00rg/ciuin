package ch.ciuin.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.ciuin.app.route.RoutePoint
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CompletableDeferred
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.kCLLocationAccuracyNearestTenMeters
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSinceNow
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
internal class IosLocationSource : LocationSource {
    private val manager = CLLocationManager()
    private var requested = false
    private var foreground = false
    private var active = false
    private var authorization: CompletableDeferred<PermissionStatus>? = null
    var permissionStatus by mutableStateOf(readStatus())
        private set
    override var current by mutableStateOf<RoutePoint?>(null)
        private set

    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            refresh()
        }

        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            if (active) (didUpdateLocations.lastOrNull() as? CLLocation)?.let(::update)
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            current = null
        }
    }

    init {
        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
    }

    suspend fun requestAuthorization(): PermissionStatus {
        refresh()
        if (permissionStatus != PermissionStatus.NOT_DETERMINED) return permissionStatus
        authorization?.let { return it.await() }
        val result = CompletableDeferred<PermissionStatus>()
        authorization = result
        manager.requestWhenInUseAuthorization()
        return result.await()
    }

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

    fun refresh() {
        permissionStatus = readStatus()
        if (permissionStatus != PermissionStatus.NOT_DETERMINED) {
            authorization?.complete(permissionStatus)
            authorization = null
        }
        val shouldUpdate = requested && foreground && permissionStatus == PermissionStatus.GRANTED
        if (shouldUpdate == active) return
        active = shouldUpdate
        if (active) {
            manager.location?.takeIf { it.timestamp.timeIntervalSinceNow in -20.0..0.0 }?.let(::update)
            manager.startUpdatingLocation()
        } else {
            manager.stopUpdatingLocation()
            current = null
        }
    }

    private fun update(location: CLLocation) {
        if (location.horizontalAccuracy < 0) return
        current = location.coordinate.useContents { RoutePoint(latitude, longitude) }.takeIf { it.isValid }
    }

    private fun readStatus(): PermissionStatus = when (manager.authorizationStatus) {
        kCLAuthorizationStatusAuthorizedAlways, kCLAuthorizationStatusAuthorizedWhenInUse -> PermissionStatus.GRANTED
        kCLAuthorizationStatusDenied, kCLAuthorizationStatusRestricted -> PermissionStatus.DENIED
        kCLAuthorizationStatusNotDetermined -> PermissionStatus.NOT_DETERMINED
        else -> PermissionStatus.UNAVAILABLE
    }
}
