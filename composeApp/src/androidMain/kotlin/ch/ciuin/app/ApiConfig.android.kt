package ch.ciuin.app

/** Set at build time from the Gradle property ciuin.apiUrl (see composeApp/build.gradle.kts). */
actual fun apiUrl(): String = BuildConfig.API_URL
