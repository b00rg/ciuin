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
 * Default everywhere: the hosted backend, https://ciuin-production.up.railway.app
 *  - Desktop: env var CIUIN_API_URL (local backend: http://localhost:8000)
 *  - Android: Gradle property, e.g. ./gradlew :composeApp:installDebug -Pciuin.apiUrl=http://10.0.2.2:8000
 *    (10.0.2.2 = the host machine, as seen from the emulator)
 *  - iOS: CIUIN_API_URL build setting (iosApp/project.yml)
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
