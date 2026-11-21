package ch.ciuin.app

import ch.ciuin.shared.api.FakePlacesApi
import ch.ciuin.shared.api.KtorPlacesApi
import ch.ciuin.shared.api.PlacesApi

/**
 * Backend URL for this platform. Override per platform:
 *  - Desktop: env var CIUIN_API_URL (default http://localhost:8000)
 *  - Android: Gradle property, e.g. ./gradlew :composeApp:installDebug -Pciuin.apiUrl=https://xyz.trycloudflare.com
 *    (default http://10.0.2.2:8000 = the host machine, as seen from the emulator)
 * Use "fake" to run against FakePlacesApi without a backend.
 */
expect fun apiUrl(): String

fun createPlacesApi(url: String = apiUrl()): PlacesApi =
    if (url.equals("fake", ignoreCase = true)) FakePlacesApi() else KtorPlacesApi(url)

fun describeApi(api: PlacesApi): String = when (api) {
    is KtorPlacesApi -> api.baseUrl
    is FakePlacesApi -> "fake data"
    else -> api::class.simpleName ?: "?"
}
