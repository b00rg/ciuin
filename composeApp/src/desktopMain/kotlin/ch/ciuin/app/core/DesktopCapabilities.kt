package ch.ciuin.app.core

import ch.ciuin.app.route.RoutePoint
import java.util.prefs.Preferences

internal class DesktopSettingsStorage : SettingsStorage {
    private val preferences = Preferences.userRoot().node("ch/ciuin")
    override fun read(): String? = preferences.get("settings", null)
    override fun write(json: String) {
        preferences.put("settings", json)
    }
}

internal object DesktopPermissions : AppPermissions {
    override fun status(permission: AppPermission) = PermissionStatus.UNAVAILABLE
    override suspend fun request(permission: AppPermission) = PermissionStatus.UNAVAILABLE
    override fun refresh() = Unit
}

internal object DesktopLocationSource : LocationSource {
    override val current: RoutePoint? = null
    override fun start() = Unit
    override fun stop() = Unit
}
