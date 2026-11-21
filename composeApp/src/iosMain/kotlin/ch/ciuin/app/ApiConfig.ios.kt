package ch.ciuin.app

import platform.Foundation.NSBundle
import platform.Foundation.NSProcessInfo

actual fun apiUrl(): String =
    (NSProcessInfo.processInfo.environment["CIUIN_API_URL"] as? String)?.takeIf { it.isNotBlank() }
        ?: (NSBundle.mainBundle.objectForInfoDictionaryKey("CiuinApiUrl") as? String)
            ?.takeIf { it.isNotBlank() }
        ?: "http://localhost:8000"
