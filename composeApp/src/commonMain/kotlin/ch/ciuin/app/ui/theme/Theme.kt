package ch.ciuin.app.ui.theme

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color

/**
 * The redesign's theme. Wrap every new screen in it.
 *
 * It provides [LocalReduceMotion], makes [CiuinType.Body] in [CiuinColors.Ink] the default text, and
 * sets a matching Material 3 colour scheme, typography and shapes so that any stray Material
 * component (a dialog, a snackbar, a text field) looks like the rest of the app. The design has one
 * appearance: slate ink on a cream ground, in light and dark system settings alike.
 *
 * @param reduceMotion pass `systemReduceMotion() || the user's own switch` from the You tab.
 */
@Composable
fun CiuinTheme(
    reduceMotion: Boolean = systemReduceMotion(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = MaterialColors, typography = CiuinType.material, shapes = MaterialShapes) {
        CompositionLocalProvider(
            LocalReduceMotion provides reduceMotion,
            LocalContentColor provides CiuinColors.Ink,
            LocalTextStyle provides CiuinType.Body,
            LocalTextSelectionColors provides SelectionColors,
            content = content,
        )
    }
}

/** Whether the system asks for reduced motion (Reduce Motion on iOS, animations off on Android). */
@Composable
expect fun systemReduceMotion(): Boolean

private val MaterialColors = lightColorScheme(
    primary = CiuinColors.Ink,
    onPrimary = CiuinColors.Ground,
    primaryContainer = CiuinColors.Wool,
    onPrimaryContainer = CiuinColors.Ink,
    inversePrimary = CiuinColors.Hoof,
    secondary = CiuinColors.Ink,
    onSecondary = CiuinColors.Ground,
    secondaryContainer = CiuinColors.Wool,
    onSecondaryContainer = CiuinColors.Ink,
    tertiary = CiuinColors.Hoof,
    onTertiary = CiuinColors.Ink,
    tertiaryContainer = CiuinColors.Wool,
    onTertiaryContainer = CiuinColors.Ink,
    background = CiuinColors.Ground,
    onBackground = CiuinColors.Ink,
    surface = CiuinColors.Ground,
    onSurface = CiuinColors.Ink,
    surfaceVariant = CiuinColors.Wool,
    onSurfaceVariant = CiuinColors.SecondaryText,
    surfaceTint = Color.Transparent,
    inverseSurface = CiuinColors.Ink,
    inverseOnSurface = CiuinColors.Ground,
    error = CiuinColors.Destructive,
    onError = CiuinColors.Ground,
    errorContainer = CiuinColors.LoudTint,
    onErrorContainer = CiuinColors.Destructive,
    outline = CiuinColors.Muted,
    outlineVariant = CiuinColors.Outline,
    scrim = CiuinColors.Ink,
    surfaceBright = CiuinColors.Ground,
    surfaceDim = CiuinColors.Wool,
    surfaceContainerLowest = CiuinColors.Ground,
    surfaceContainerLow = CiuinColors.Ground,
    surfaceContainer = CiuinColors.Tile,
    surfaceContainerHigh = CiuinColors.Tile,
    surfaceContainerHighest = CiuinColors.Wool,
)

private val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(CiuinRadii.Plate),
    small = RoundedCornerShape(CiuinRadii.TileInner),
    medium = RoundedCornerShape(CiuinRadii.Option),
    large = RoundedCornerShape(CiuinRadii.Card),
    extraLarge = RoundedCornerShape(CiuinRadii.Sheet),
)

private val SelectionColors = TextSelectionColors(
    handleColor = CiuinColors.Ink,
    backgroundColor = CiuinColors.Hoof.copy(alpha = 0.4f),
)
