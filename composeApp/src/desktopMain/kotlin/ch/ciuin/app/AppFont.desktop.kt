package ch.ciuin.app

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/** Poppins from the classpath, where Gradle puts the shared `src/commonMain/fontRes` directory. */
internal actual val appFontFamily: FontFamily = FontFamily(
    Font(resource = "font/poppins_regular.ttf", weight = FontWeight.Normal),
    Font(resource = "font/poppins_medium.ttf", weight = FontWeight.Medium),
    Font(resource = "font/poppins_semibold.ttf", weight = FontWeight.SemiBold),
    Font(resource = "font/poppins_bold.ttf", weight = FontWeight.Bold),
)

internal actual val displayFontFamily: FontFamily =
    FontFamily(Font(resource = "font/pressworn_regular.ttf", weight = FontWeight.Normal))

internal actual val symbolFontFamily: FontFamily? = null
