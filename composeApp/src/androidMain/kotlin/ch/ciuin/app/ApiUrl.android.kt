package ch.ciuin.app

/** Set at build time with `-Pciuin.apiUrl=http://<laptop-ip>:8000`; defaults to the emulator's host alias. */
actual fun apiBaseUrl(): String = BuildConfig.API_URL
