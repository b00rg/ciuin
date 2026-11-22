package ch.ciuin.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Every colour in the redesign, named by role. Values are the ones on the "Foundations" board and
 * the screen artboards in Paper.
 *
 * The ground is white and the type is black; the one warm accent is the sheep's hoof yellow. Green,
 * amber and red are kept for sensory data, and a level is never shown by colour alone: it always
 * comes with a shape and a word (see `Level` in `ui/components`).
 */
object CiuinColors {
    // The eight named on the Foundations board.
    /** Screen background and every raised surface. */
    val Ground = Color(0xFFFFFFFF)

    /** Text, icons, the primary button, selected chips and the black banner. */
    val Ink = Color(0xFF0A0A0A)

    /** Warm grey of the sheep's wool: character cards, icon discs, the "Allowed" pill, the segmented control. */
    val Wool = Color(0xFFF1EFEC)

    /** The sheep's hoof yellow, the one warm accent. */
    val Hoof = Color(0xFFEDAE2B)

    /** Calm level: always drawn as a circle. */
    val Calm = Color(0xFF1F8A5B)

    /** Moderate level: always drawn as a diamond. */
    val Moderate = Color(0xFFE8A317)

    /** Loud or too much: always drawn as a square. */
    val Loud = Color(0xFFD93A2B)

    /** Supporting text; 7:1 on [Ground]. */
    val SecondaryText = Color(0xFF5A5A5A)

    // Text and marks.
    /** Text and icons on [Ink]. */
    val OnInk = Ground

    /** Second line of text on [Ink] (offline banner, walking header). */
    val OnInkSecondary = Color(0xFFD6D6D6)

    /** Destructive text and outline: "Reset what the sheep learned", the Reset button. */
    val Destructive = Color(0xFFB92C1F)

    /** The dashed no-data ring, the empty radio ring, the title of an unavailable option, the sheep's z's. */
    val Muted = Color(0xFF8A8A8A)

    /** Switch track when off and the dashed ring of an unavailable radio. */
    val Disabled = Color(0xFFBDBDBD)

    // Surfaces and lines.
    /** Quiet grey tile: stat tiles, tag chips, the nearest-spot card, the round close button, the Live pill. */
    val Tile = Color(0xFFF6F5F3)

    /** Hairline between list rows, and the track of a progress bar. */
    val Hairline = Color(0xFFEFEFEF)

    /** Slightly stronger hairline: permission rows, the tab bar's top edge, the white colour swatch. */
    val HairlineStrong = Color(0xFFE9E9E9)

    /** Border of an unselected chip or selectable card. */
    val Outline = Color(0xFFE0E0E0)

    /** Border of white controls that float over the map: search field, pins, locate button, the quiet-place card. */
    val FloatingOutline = Color(0xFFE4E4E4)

    /** Drag handle of a sheet and the page dots not yet reached. */
    val Handle = Color(0xFFD6D6D6)

    /** Track of the measure ring. */
    val RingTrack = Color(0xFFECECEC)

    /** Dimmed backdrop behind a confirmation sheet: ink at 40%. */
    val Scrim = Color(0x660A0A0A)

    /** Plate behind the map attribution: white at 85%. */
    val AttributionPlate = Color(0xD9FFFFFF)

    // Character scenes.
    /** The ground curve the sheep stands on. */
    val Pasture = Color(0xFFE7E4DF)

    /** The duck pond. */
    val Pond = Color(0xFFDCE6EA)

    /** Ground line and the hop's own shadow on the Motion board. */
    val GroundLine = Color(0xFFD9D5D0)

    /** Dotted hop arc, and the dashed outline of a hexagon with no data. */
    val DottedLine = Color(0xFFB9B4AE)

    // Level tints: the disc behind a level glyph in a map pin.
    val CalmTint = Color(0xFFE4F1EA)

    /** Not on any artboard (no moderate pin is drawn); mixed the same way as the other two, 12% over white. */
    val ModerateTint = Color(0xFFFCF4E3)
    val LoudTint = Color(0xFFFBE5E2)

    // The map itself.
    val MapGround = Color(0xFFF3F2F0)
    val MapPark = Color(0xFFE8ECE4)
    val MapWater = Color(0xFFE3EAEE)
    val MapRoad = Color(0xFFFFFFFF)
    val MapRail = Color(0xFFDAD6D1)

    /** Fill opacity of a hexagon over the map, per level. Live data, then data saved for offline use. */
    const val HexAlphaCalm = 0.36f
    const val HexAlphaModerate = 0.30f
    const val HexAlphaLoud = 0.44f
    const val HexAlphaCalmSaved = 0.22f
    const val HexAlphaModerateSaved = 0.18f
    const val HexAlphaLoudSaved = 0.26f

    /** Halo around the "you are here" dot: ink at 10%. */
    val LocationHalo = Color(0x1A0A0A0A)

    /** White veil that fades the map out under the search row. Top, middle and bottom stops. */
    val VeilTop = Color(0xF5FFFFFF)
    val VeilMiddle = Color(0xE6FFFFFF)
    val VeilBottom = Color(0x00FFFFFF)
}
