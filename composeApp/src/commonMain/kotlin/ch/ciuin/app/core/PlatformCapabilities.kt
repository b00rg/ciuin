package ch.ciuin.app.core

import ch.ciuin.app.route.RoutePoint

internal enum class AppPermission { MICROPHONE, CAMERA, LOCATION, NOTIFICATIONS }
internal enum class PermissionStatus { GRANTED, DENIED, NOT_DETERMINED, UNAVAILABLE }

internal interface AppPermissions {
    fun status(permission: AppPermission): PermissionStatus
    suspend fun request(permission: AppPermission): PermissionStatus
    fun refresh()
}

internal interface LocationSource {
    val current: RoutePoint?
    fun start()
    fun stop()
}

internal interface ListeningControl {
    val isListening: Boolean
    val message: String?
    val liveScore: Double?
    val liveDb: Double?
    val todayStats: ListeningStats?
    fun setListening(listening: Boolean)
}

/** Time classified by what the listener ran, in the device's local calendar day. */
internal data class ListeningStats(
    val asleepMillis: Long = 0,
    val smallModelMillis: Long = 0,
    val largeModelMillis: Long = 0,
) {
    val totalMillis: Long get() = asleepMillis + smallModelMillis + largeModelMillis
    val asleepShare: Double get() = share(asleepMillis)
    val smallModelShare: Double get() = share(smallModelMillis)
    val largeModelShare: Double get() = share(largeModelMillis)

    private fun share(millis: Long) = if (totalMillis == 0L) 0.0 else millis.toDouble() / totalMillis
}
