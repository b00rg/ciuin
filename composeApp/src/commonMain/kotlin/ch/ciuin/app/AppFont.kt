package ch.ciuin.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/**
 * Poppins, the typeface for all app text: weights 400, 500, 600 and 700 from the same four files on
 * every platform (`composeApp/src/commonMain/fontRes/font`), so text measures the same everywhere.
 *
 * The files are the ones from google/fonts with one change: their vertical metrics are trimmed.
 * Poppins ships with an ascender of 1050 and a descender of -350 (1.4 em), and from Android 15 a line
 * can no longer be shorter than its font's own metrics, so the design's tight headlines (40/44, 32/38)
 * came out up to 12 dp per line taller on Android than on iOS. In `hhea` and in the `OS/2` typo fields
 * the ascender is now 900, the descender -200 and the line gap 0 (1.1 em, the tightest ratio in the
 * type scale). The middle of the line stays where it was, so glyphs sit exactly as before; the outlines,
 * advances and kerning are untouched. The `DSIG` table is dropped because the signature no longer holds.
 *
 * New screens should not use this directly: `CiuinTheme` and `CiuinType` in `ui/theme` already apply it.
 */
internal expect val appFontFamily: FontFamily

/**
 * Typeface for symbols Poppins lacks (the "⌂" button), or null to let the platform fall back.
 * Android falls back to Noto Sans Symbols; platforms whose fallback differs supply that glyph here.
 */
internal expect val symbolFontFamily: FontFamily?

/** The Material 3 type scale in [appFontFamily], for the screens that predate `CiuinTheme`. */
@Composable
internal fun appTypography(): Typography {
    val base = MaterialTheme.typography
    val family = appFontFamily
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
