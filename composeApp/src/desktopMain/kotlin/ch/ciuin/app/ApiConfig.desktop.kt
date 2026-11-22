package ch.ciuin.app

actual fun apiUrl(): String =
    System.getenv("CIUIN_API_URL")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("ciuin.apiUrl")?.takeIf { it.isNotBlank() }
        ?: "https://ciuin-production.up.railway.app"
