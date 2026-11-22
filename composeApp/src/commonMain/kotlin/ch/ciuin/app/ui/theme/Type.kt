package ch.ciuin.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ch.ciuin.app.appFontFamily
import ch.ciuin.app.displayFontFamily

/** Poppins in the four weights the design uses (400, 500, 600, 700), identical on every platform. */
val Poppins: FontFamily get() = appFontFamily

/**
 * Pressworn, the worn-letterpress face from the moodboard, in its one weight. Only for the display
 * styles below, and only for fixed English text: it has no accented letters, so a place name, the
 * "ciúin" wordmark or anything from the data must stay in [Poppins].
 */
val Pressworn: FontFamily get() = displayFontFamily

/**
 * The type scale: every text style on the Foundations board and the screen artboards, with Paper's
 * size, line height, weight and tracking. Styles carry no colour, so text takes the theme's
 * [CiuinColors.Ink] (light or dark); pass a colour for secondary text.
 *
 * Each line is exactly its line height tall with the glyphs centred in it, as in CSS, so a text
 * block measures what Paper shows (a two-line 16/24 paragraph is 48 high on every platform).
 *
 * No style may set a line height below 1.1 times its size: that is the height of the bundled
 * Poppins and Pressworn, and Android 15 will not draw a line shorter than its font (see `appFontFamily`).
 *
 * [Hero], [Display], [ScreenTitle] and [HeadlineTight] are set in [Pressworn]: give them fixed English
 * headings and numbers only, never a place name or other text with accents.
 */
object CiuinType {
    // Display headlines in Pressworn (fixed English text only).
    /** 40/44. Onboarding heroes: "Find your quiet.", "You're all set." */
    val Hero = display(40, 44)

    /** 40/44. The board's "40"; the measure countdown "18 seconds left". */
    val Display = display(40, 44)

    /** 32/38. Screen titles ("Measure", "You", "It's getting loud here.") and the big reading "60 dB". */
    val ScreenTitle = display(32, 38)

    // Headlines in Poppins, weight 600.

    /** 28/34, -0.01em. The board's "28 / 600". */
    val Headline = style(28, 34, FontWeight.SemiBold, (-0.01).em)

    /** 28/34, Pressworn. "Only numbers leave this phone." on the privacy screen. */
    val HeadlineTight = display(28, 34)

    /** 24/30, -0.01em. Place name on the place sheet; title of a confirmation sheet. */
    val PlaceTitle = style(24, 30, FontWeight.SemiBold, (-0.01).em)

    /** 22/28, -0.01em. The verdict in a map sheet: "Calm for you", "No light readings yet". */
    val SheetTitle = style(22, 28, FontWeight.SemiBold, (-0.01).em)

    /** 20/28. The board's "20 / 600"; the title in a top bar. */
    val Title = style(20, 28, FontWeight.SemiBold)

    /** 20/28, -0.01em. The "ciúin" wordmark. */
    val Wordmark = style(20, 28, FontWeight.SemiBold, (-0.01).em)

    /** 20/26. The number in a stat tile. */
    val StatValue = style(20, 26, FontWeight.SemiBold)

    /** 18/24. Title of a wool card ("Likely fine for you", "Student S") and the number in a small white tile. */
    val CardTitle = style(18, 24, FontWeight.SemiBold)

    /** 16/24, weight 600. Title of an onboarding preset card. */
    val OptionTitle = style(16, 24, FontWeight.SemiBold)

    /** 16/22, weight 600. Title in a compact card, the walking header, a reading's value, the place being measured. */
    val Subtitle = style(16, 22, FontWeight.SemiBold)

    // Labels, weight 500.
    /** 16/20. Label of a 56-high button and of a text button ("Skip", "Cancel", "Discard"). */
    val Button = style(16, 20, FontWeight.Medium)

    /** 16/22. Title of a list or settings row; the level word next to a big reading. */
    val RowTitle = style(16, 22, FontWeight.Medium)

    /** 15/22. Title in a grey tile, the name of a dimension, the first route step, a one-line summary. */
    val TileTitle = style(15, 22, FontWeight.Medium)

    /** 15/18. Label of a 48 or 52-high button and of the "Reset" link. */
    val ButtonMedium = style(15, 18, FontWeight.Medium)

    /** 14/20. The board's "14 / 500": labels, level words, the banner title. */
    val Label = style(14, 20, FontWeight.Medium)

    /** 14/18. Label of a layer chip and of a 40 or 44-high button. */
    val Chip = style(14, 18, FontWeight.Medium)

    /** 13/18. Eyebrow above a title, section labels, legend words, pin labels. */
    val Eyebrow = style(13, 18, FontWeight.Medium)

    /** 13/16. Label of a tag chip, a segment, or a small pill ("New", "Lausanne"). */
    val Pill = style(13, 16, FontWeight.Medium)

    /** 12/16, weight 600. Label of the selected tab. */
    val TabSelected = style(12, 16, FontWeight.SemiBold)

    /** 12/16. Label of the other tabs. */
    val Tab = style(12, 16, FontWeight.Medium)

    /** 14/18, weight 600. The digit in a numbered step. */
    val StepNumber = style(14, 18, FontWeight.SemiBold)

    // Running text, weight 400.
    /** 16/24. The board's "16 / 400": body text. */
    val Body = style(16, 24, FontWeight.Normal)

    /** 16/22. Single-line text in a list: tips, recent searches, the privacy lists. */
    val BodyCompact = style(16, 22, FontWeight.Normal)

    /** 15/22. The level word in a dimension row; later route steps. */
    val BodyMedium = style(15, 22, FontWeight.Normal)

    /** 14/20. Supporting text under a title; checklist lines; bar labels. */
    val BodySmall = style(14, 20, FontWeight.Normal)

    /** 13/18. The board's "13 / 400", the smallest running size: captions and second lines of rows. */
    val Caption = style(13, 18, FontWeight.Normal)

    /** 11/16. Map attribution only. */
    val Attribution = style(11, 16, FontWeight.Normal)

    /** Poppins 700 for the sheep's drifting z's; the size is set by the drawing. */
    val Zzz: TextStyle get() = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Bold, color = CiuinColors.Muted)

    /** Every style with its name, in the order above. For the gallery and for tests. */
    val all: List<Pair<String, TextStyle>>
        get() = listOf(
            "Hero" to Hero, "Display" to Display, "ScreenTitle" to ScreenTitle, "Headline" to Headline,
            "HeadlineTight" to HeadlineTight, "PlaceTitle" to PlaceTitle, "SheetTitle" to SheetTitle,
            "Title" to Title, "Wordmark" to Wordmark, "StatValue" to StatValue, "CardTitle" to CardTitle,
            "OptionTitle" to OptionTitle, "Subtitle" to Subtitle, "Button" to Button, "RowTitle" to RowTitle,
            "TileTitle" to TileTitle, "ButtonMedium" to ButtonMedium, "Label" to Label, "Chip" to Chip,
            "Eyebrow" to Eyebrow, "Pill" to Pill, "TabSelected" to TabSelected, "Tab" to Tab,
            "StepNumber" to StepNumber, "Body" to Body, "BodyCompact" to BodyCompact, "BodyMedium" to BodyMedium,
            "BodySmall" to BodySmall, "Caption" to Caption, "Attribution" to Attribution,
        )

    /** The Material 3 type scale mapped onto the styles above, so a stray Material component looks right. */
    internal val material: Typography
        get() = Typography(
            displayLarge = Hero,
            displayMedium = Display,
            displaySmall = Display,
            headlineLarge = ScreenTitle,
            headlineMedium = Headline,
            headlineSmall = PlaceTitle,
            titleLarge = SheetTitle,
            titleMedium = RowTitle,
            titleSmall = Label,
            bodyLarge = Body,
            bodyMedium = BodySmall,
            bodySmall = Caption,
            labelLarge = Button,
            labelMedium = Eyebrow,
            labelSmall = Tab,
        )
}

/** CSS-like line boxes: the line is exactly [lineHeight] tall and the glyphs sit in its middle. */
internal val CssLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun display(size: Int, lineHeight: Int) =
    style(size, lineHeight, FontWeight.Normal).copy(fontFamily = Pressworn)

private fun style(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    tracking: TextUnit = 0.em,
) = TextStyle(
    fontFamily = Poppins,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking,
    lineHeightStyle = CssLineHeight,
)
