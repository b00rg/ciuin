package ch.ciuin.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSProcessInfo
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

actual fun apiUrl(): String =
    (NSProcessInfo.processInfo.environment["CIUIN_API_URL"] as? String)?.takeIf { it.isNotBlank() }
        ?: (NSBundle.mainBundle.objectForInfoDictionaryKey("CiuinApiUrl") as? String)
            ?.takeIf { it.isNotBlank() }
        ?: "http://localhost:8000"

@OptIn(ExperimentalForeignApi::class)
internal fun readBundledPlaces(): String? {
    val path = NSBundle.mainBundle.pathForResource(BUNDLED_PLACES_NAME, BUNDLED_PLACES_EXTENSION) ?: return null
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    if (data.length == 0uL) return ""
    val bytes = ByteArray(data.length.toInt())
    bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return bytes.decodeToString()
}
