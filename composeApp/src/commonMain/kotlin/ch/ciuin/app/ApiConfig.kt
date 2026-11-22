package ch.ciuin.app

import ch.ciuin.shared.api.FakePlacesApi
import ch.ciuin.shared.api.FallbackPlacesApi
import ch.ciuin.shared.api.KtorPlacesApi
import ch.ciuin.shared.api.PlacesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Backend URL for this platform. Override per platform:
 *  - Desktop: env var CIUIN_API_URL (default http://localhost:8000)
 *  - Android: Gradle property, e.g. ./gradlew :composeApp:installDebug -Pciuin.apiUrl=https://xyz.trycloudflare.com
 *    (default http://10.0.2.2:8000 = the host machine, as seen from the emulator)
 * Use "fake" to run against FakePlacesApi without a backend.
 */
expect fun apiUrl(): String

/** Read the bundled places on the first offline request. Explicit fake mode always uses samples. */
internal fun createPlacesApi(bundled: BundledPlacesApi, url: String = apiUrl()): PlacesApi {
    if (url.equals("fake", ignoreCase = true)) return FakePlacesApi()
    val offline by lazy { if (bundled.available) bundled else FakePlacesApi() }
    return FallbackPlacesApi(KtorPlacesApi(url)) {
        withContext(Dispatchers.IO) { offline }
    }
}

fun describeApi(api: PlacesApi): String = when (api) {
    is KtorPlacesApi -> api.baseUrl
    is FallbackPlacesApi -> describeApi(api.primary)
    is FakePlacesApi -> "fake data"
    else -> api::class.simpleName ?: "?"
}
