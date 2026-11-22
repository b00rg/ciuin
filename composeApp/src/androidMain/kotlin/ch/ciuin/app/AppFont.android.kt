package ch.ciuin.app

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** Poppins from `res/font`, which Gradle points at the shared `src/commonMain/fontRes` directory. */
internal actual val appFontFamily: FontFamily = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

internal actual val displayFontFamily: FontFamily = FontFamily(Font(R.font.pressworn_regular, FontWeight.Normal))

internal actual val symbolFontFamily: FontFamily? = null
