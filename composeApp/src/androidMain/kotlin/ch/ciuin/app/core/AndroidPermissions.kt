package ch.ciuin.app.core

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AndroidPermissions(private val activity: ComponentActivity) : AppPermissions {
    private val requested = activity.getSharedPreferences("requested_permissions", 0)
    private val statuses = mutableStateMapOf<AppPermission, PermissionStatus>()
    private val requests = Mutex()
    private var pending: CompletableDeferred<Unit>? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        requested.edit().apply { result.keys.forEach { putBoolean(it, true) } }.apply()
        refresh()
        val completion = pending
        pending = null
        completion?.complete(Unit)
    }

    init { refresh() }

    override fun status(permission: AppPermission): PermissionStatus = statuses.getValue(permission)

    override suspend fun request(permission: AppPermission): PermissionStatus {
        refresh()
        if (status(permission) != PermissionStatus.GRANTED && status(permission) != PermissionStatus.UNAVAILABLE) {
            requestSystem(runtimePermissions(permission))
        }
        return status(permission)
    }

    suspend fun requestPreciseLocation() {
        requestSystem(runtimePermissions(AppPermission.LOCATION), requestTogether = true)
    }

    /** Capture includes Bluetooth in the same Android prompt as the microphone and camera. */
    suspend fun requestSystem(permissions: List<String>, requestTogether: Boolean = false) = requests.withLock {
        pending?.await()
        val missing = permissions.filterNot(::has)
        if (missing.isEmpty()) return@withLock
        val completion = CompletableDeferred<Unit>()
        pending = completion
        try {
            launcher.launch((if (requestTogether) permissions else missing).toTypedArray())
        } catch (error: RuntimeException) {
            pending = null
            throw error
        }
        completion.await()
    }

    override fun refresh() {
        for (permission in AppPermission.entries) statuses[permission] = read(permission)
    }

    private fun read(permission: AppPermission): PermissionStatus {
        val hardware = when (permission) {
            AppPermission.MICROPHONE -> PackageManager.FEATURE_MICROPHONE
            AppPermission.CAMERA -> PackageManager.FEATURE_CAMERA_ANY
            AppPermission.LOCATION -> PackageManager.FEATURE_LOCATION
            AppPermission.NOTIFICATIONS -> null
        }
        if (hardware != null && !activity.packageManager.hasSystemFeature(hardware)) return PermissionStatus.UNAVAILABLE
        val names = runtimePermissions(permission)
        if (permission == AppPermission.NOTIFICATIONS && (names.isEmpty() || names.any(::has))) {
            val enabled = activity.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
            return if (enabled) PermissionStatus.GRANTED else PermissionStatus.DENIED
        }
        return when {
            names.any(::has) -> PermissionStatus.GRANTED
            names.any { requested.getBoolean(it, false) || activity.shouldShowRequestPermissionRationale(it) } -> PermissionStatus.DENIED
            else -> PermissionStatus.NOT_DETERMINED
        }
    }

    private fun has(permission: String) = activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun runtimePermissions(permission: AppPermission): List<String> = when (permission) {
        AppPermission.MICROPHONE -> listOf(Manifest.permission.RECORD_AUDIO)
        AppPermission.CAMERA -> listOf(Manifest.permission.CAMERA)
        AppPermission.LOCATION -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        AppPermission.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    }
}
