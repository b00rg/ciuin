package ch.ciuin.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/**
 * Typeface for all app text, or null to use the platform default.
 * Android's default is Roboto and is the reference look; platforms whose default differs supply Roboto here.
 */
internal expect val appFontFamily: FontFamily?

/**
 * Typeface for symbols Roboto lacks (the "⌂" button), or null to let the platform fall back.
 * Android falls back to Noto Sans Symbols; platforms whose fallback differs supply that glyph here.
 */
internal expect val symbolFontFamily: FontFamily?

/** The Material 3 type scale, in [appFontFamily] where the platform supplies one. */
@Composable
internal fun appTypography(): Typography {
    val base = MaterialTheme.typography
    val family = appFontFamily ?: return base
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
}
