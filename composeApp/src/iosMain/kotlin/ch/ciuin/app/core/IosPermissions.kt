package ch.ciuin.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
internal class IosPermissions(
    private val scope: CoroutineScope,
    private val location: IosLocationSource,
) : AppPermissions {
    private val audio = checkNotNull(AVMediaTypeAudio)
    private val video = checkNotNull(AVMediaTypeVideo)
    private val mediaStatuses = mutableStateMapOf<String, PermissionStatus>()
    private val center = UNUserNotificationCenter.currentNotificationCenter()
    private var notifications by mutableStateOf(PermissionStatus.NOT_DETERMINED)
    private var notificationRefresh: Job? = null

    init { refresh() }

    override fun status(permission: AppPermission): PermissionStatus = when (permission) {
        AppPermission.MICROPHONE -> mediaStatuses.getValue(audio)
        AppPermission.CAMERA -> mediaStatuses.getValue(video)
        AppPermission.LOCATION -> location.permissionStatus
        AppPermission.NOTIFICATIONS -> notifications
    }

    override suspend fun request(permission: AppPermission): PermissionStatus = when (permission) {
        AppPermission.MICROPHONE -> requestMedia(audio)
        AppPermission.CAMERA -> requestMedia(video)
        AppPermission.LOCATION -> location.requestAuthorization()
        AppPermission.NOTIFICATIONS -> {
            if (readNotifications() == PermissionStatus.NOT_DETERMINED) {
                suspendCancellableCoroutine { continuation ->
                    center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { _, _ ->
                        continuation.resume(Unit)
                    }
                }
            }
            refreshNotifications()
        }
    }

    override fun refresh() {
        mediaStatuses[audio] = readMedia(audio)
        mediaStatuses[video] = readMedia(video)
        location.refresh()
        notificationRefresh?.cancel()
        notificationRefresh = scope.launch { refreshNotifications() }
    }

    private suspend fun requestMedia(type: String): PermissionStatus {
        if (readMedia(type) == PermissionStatus.NOT_DETERMINED) {
            suspendCancellableCoroutine { continuation ->
                AVCaptureDevice.requestAccessForMediaType(type) { continuation.resume(Unit) }
            }
        }
        return readMedia(type).also { mediaStatuses[type] = it }
    }

    private fun readMedia(type: String): PermissionStatus {
        if (AVCaptureDevice.defaultDeviceWithMediaType(type) == null) return PermissionStatus.UNAVAILABLE
        return when (AVCaptureDevice.authorizationStatusForMediaType(type)) {
            AVAuthorizationStatusAuthorized -> PermissionStatus.GRANTED
            AVAuthorizationStatusDenied, AVAuthorizationStatusRestricted -> PermissionStatus.DENIED
            AVAuthorizationStatusNotDetermined -> PermissionStatus.NOT_DETERMINED
            else -> PermissionStatus.UNAVAILABLE
        }
    }

    private suspend fun refreshNotifications(): PermissionStatus = readNotifications().also { notifications = it }

    private suspend fun readNotifications(): PermissionStatus = suspendCancellableCoroutine { continuation ->
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val status = when (settings?.authorizationStatus) {
                UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional, UNAuthorizationStatusEphemeral ->
                    PermissionStatus.GRANTED
                UNAuthorizationStatusDenied -> PermissionStatus.DENIED
                UNAuthorizationStatusNotDetermined -> PermissionStatus.NOT_DETERMINED
                else -> PermissionStatus.UNAVAILABLE
            }
            continuation.resume(status)
        }
    }
}
