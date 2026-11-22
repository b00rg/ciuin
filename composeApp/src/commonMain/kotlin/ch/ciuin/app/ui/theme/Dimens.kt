package ch.ciuin.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** Corner radii. Paper uses plain circular corners, so these are exact with [RoundedCornerShape]. */
object CiuinRadii {
    /** Character scenes: the pasture on onboarding, the alert and the shared screen. */
    val Scene = 40.dp

    /** The pond card on You. */
    val SceneSmall = 32.dp

    /** Top corners of a bottom sheet; the privacy tile; a 56 disc. */
    val Sheet = 28.dp

    /** Wool cards, the model card, the quiet-place card, the walking header. */
    val Card = 24.dp

    /** The verdict card on the place sheet. */
    val CardCompact = 22.dp

    /** Selectable cards, the legend card, the offline banner. */
    val Option = 20.dp

    /** Grey tiles: stats, nearest spot, summary row. */
    val Tile = 18.dp

    /** White tiles inside a wool card. */
    val TileInner = 16.dp

    /** The attribution plate. */
    val Plate = 8.dp

    /** Progress bar track and fill. */
    val Bar = 4.dp

    /** Corners of the diamond and square level glyphs. */
    val Glyph = 2.dp
}

/** The spacing steps that recur across the artboards. */
object CiuinSpacing {
    /** Left and right margin of a full screen. */
    val Screen = 24.dp

    /** Left and right padding inside a bottom sheet. */
    val Sheet = 20.dp

    /** Left and right margin of controls floating over the map. */
    val Map = 16.dp

    /** Gap between the blocks of a screen (title, list, card). */
    val Section = 20.dp

    /** Gap between the blocks of a sheet. */
    val SheetSection = 16.dp

    /** Gap between a headline and its paragraph. */
    val Copy = 10.dp

    /** Gap between stacked cards. */
    val Cards = 12.dp

    /** Gap between two buttons or tiles side by side. */
    val Pair = 10.dp

    /** Gap between chips. */
    val Chips = 8.dp

    /** Gap between a leading glyph or icon and its text in a row. */
    val RowGap = 12.dp

    /** Gap between a picture and the text beside it in a card. */
    val CardGap = 14.dp

    /** Gap between a title and the line under it. */
    val TitleGap = 2.dp

    /** Height of the top bar and of any text button. */
    val TopBar = 48.dp

    /** Smallest touch target. */
    val MinTarget = 48.dp
}

/**
 * A soft shadow as Paper specifies it: a CSS box shadow with an offset, a blur and a colour.
 * Drawn with [dropShadow] rather than Material elevation, so it looks the same on Android and iOS.
 */
class CiuinShadow(val offsetY: Dp, val blur: Dp, val color: Color)

/** The shadows in the design. Everything else is flat. */
object CiuinElevation {
    /** Search field and the round listening button over the map. */
    val Search = CiuinShadow(2.dp, 10.dp, Color(0x0F0A0A0A))

    /** A level pin on the map. */
    val Pin = CiuinShadow(2.dp, 8.dp, Color(0x140A0A0A))

    /** The round locate button. */
    val Floating = CiuinShadow(2.dp, 10.dp, Color(0x140A0A0A))

    /** The black pin of the selected place. */
    val SelectedPin = CiuinShadow(4.dp, 14.dp, Color(0x330A0A0A))

    /** A bottom sheet; the shadow falls upward. */
    val Sheet = CiuinShadow((-4).dp, 24.dp, Color(0x140A0A0A))

    /** The "you are here" dot. */
    val LocationDot = CiuinShadow(1.dp, 4.dp, Color(0x4D0A0A0A))
}

/** Draws [shadow] behind a surface of the given [shape]. Put it before the background. */
fun Modifier.ciuinShadow(shadow: CiuinShadow, shape: Shape): Modifier = dropShadow(
    shape = shape,
    shadow = Shadow(
        // CSS blurs with a standard deviation of half the blur radius; Compose derives it as
        // radius * 0.57735 + 0.5 px, so this radius gives the same softness.
        radius = shadow.blur * CSS_BLUR_TO_RADIUS,
        color = shadow.color,
        offset = DpOffset(0.dp, shadow.offsetY),
    ),
)

private const val CSS_BLUR_TO_RADIUS = 0.866f
