package ch.ciuin.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Every colour in the app, named by role.
 *
 * The palette is the moodboard's (`assets/colourpalette.webp`): slate type on a cream ground, mist-blue
 * cards, sage for the one calm accent and soft grey hairlines. The sheep keeps its hoof yellow. Calm,
 * moderate and loud are a deep sage, a muted ochre and a dusty terracotta, and a level is never shown
 * by colour alone: it always comes with a shape and a word (see `Level` in `ui/components`).
 *
 * Contrast, measured against [Ground] unless noted: [Ink] 7.2:1 (5.7:1 on [Wool]); [SecondaryText]
 * 5.9:1 (4.7:1 on [Wool], 4.9:1 on [Tile]); level glyphs at least 3:1. The three levels stay apart
 * under protan, deutan and tritan simulation at least as well as the old green, amber and red did.
 */
object CiuinColors {
    // The moodboard.
    /** Slate: text, icons, the primary button, selected chips and the dark banner. */
    private val Slate = Color(0xFF505569)

    /** Sage: the calm accent. */
    private val Sage = Color(0xFF9EB0AC)

    /** Mist blue, the moodboard swatch itself. */
    private val Mist = Color(0xFFCFD5DF)

    /** Cream: the ground. */
    private val Cream = Color(0xFFFDFFDC)

    /** Soft grey: hairlines. */
    private val SoftGrey = Color(0xFFDBDBDB)

    // The core roles.
    /** Screen background and every raised surface. */
    val Ground = Cream

    /** Text, icons, the primary button, selected chips and the dark banner. */
    val Ink = Slate

    /**
     * Mist blue for character cards, icon discs, the "Allowed" pill, the segmented control. Lighter
     * than the swatch so that [SecondaryText] still reads at 4.5:1 on it.
     */
    val Wool = Color(0xFFDDE2E9)

    /** The calm accent: the measuring waveform and other quiet highlights. Decorative only, not for text. */
    val Accent = Sage

    /** The sheep's hoof yellow, kept for the characters. */
    val Hoof = Color(0xFFEDAE2B)

    /** Calm level, a deep sage: always drawn as a circle. */
    val Calm = Color(0xFF4F8580)

    /** Moderate level, a muted ochre: always drawn as a diamond. */
    val Moderate = Color(0xFFB38A2E)

    /** Loud or too much, a dusty terracotta: always drawn as a square. */
    val Loud = Color(0xFFA04A34)

    /** Supporting text: 5.9:1 on [Ground], 4.7:1 on [Wool]. */
    val SecondaryText = Color(0xFF5E6273)

    // Text and marks.
    /** Text and icons on [Ink]. */
    val OnInk = Ground

    /** Second line of text on [Ink] (offline banner, walking header); 5:1. */
    val OnInkSecondary = Mist

    /** Destructive text and outline: "Reset what the sheep learned", the Reset button. */
    val Destructive = Color(0xFF8E3B2A)

    /** The dashed no-data ring, the empty radio ring, the title of an unavailable option, the sheep's z's. */
    val Muted = Color(0xFF8A8F9E)

    /** Switch track when off and the dashed ring of an unavailable radio. */
    val Disabled = Color(0xFFBCC0C8)

    // Surfaces and lines.
    /** Quiet mist tile: stat tiles, tag chips, the nearest-spot card, the round close button, the Live pill. */
    val Tile = Color(0xFFE5E8ED)

    /** Hairline between list rows, and the track of a progress bar. */
    val Hairline = SoftGrey

    /** Slightly stronger hairline: permission rows, the tab bar's top edge, the ground colour swatch. */
    val HairlineStrong = Color(0xFFD3D4CF)

    /** Border of an unselected chip or selectable card. */
    val Outline = Color(0xFFCDCFCB)

    /** Border of cream controls that float over the map: search field, pins, locate button, the quiet-place card. */
    val FloatingOutline = Color(0xFFD6D8D0)

    /** Drag handle of a sheet and the page dots not yet reached. */
    val Handle = Color(0xFFC9CDD4)

    /** Track of the measure ring and the quiet bars of the waveform. */
    val RingTrack = Color(0xFFE6E8D2)

    /** Dimmed backdrop behind a confirmation sheet: slate at 40%. */
    val Scrim = Color(0x66505569)

    /** Plate behind the map attribution: cream at 85%. */
    val AttributionPlate = Color(0xD9FDFFDC)

    // Character scenes.
    /** The ground curve the sheep stands on: cream with a little sage. */
    val Pasture = Color(0xFFE5EBD0)

    /** The duck pond. */
    val Pond = Mist

    /** Ground line and the hop's own shadow on the Motion board. */
    val GroundLine = Color(0xFFD5D9C4)

    /** Dotted hop arc, and the dashed outline of a hexagon with no data. */
    val DottedLine = Color(0xFFB4B8A8)

    // Level tints: the disc behind a level glyph in a map pin, each level at 14% over [Ground].
    val CalmTint = Color(0xFFE5EECF)
    val ModerateTint = Color(0xFFF3EFC4)
    val LoudTint = Color(0xFFF0E6C4)

    // The map itself: cream land, sage parks, mist water.
    val MapGround = Color(0xFFF4F6D6)
    val MapPark = Color(0xFFE8EED1)
    val MapWater = Color(0xFFE1E6DE)
    val MapRoad = Color(0xFFFEFFEE)
    val MapRail = Color(0xFFD3D5C6)

    /**
     * Fill opacity of a hexagon over the map, per level. Live data, then data saved for offline use.
     * The levels are deeper than the old green, amber and red, so they sit a little lighter.
     */
    const val HexAlphaCalm = 0.32f
    const val HexAlphaModerate = 0.30f
    const val HexAlphaLoud = 0.36f
    const val HexAlphaCalmSaved = 0.20f
    const val HexAlphaModerateSaved = 0.18f
    const val HexAlphaLoudSaved = 0.22f

    /** Halo around the "you are here" dot: slate at 10%. */
    val LocationHalo = Color(0x1A505569)

    /** Cream veil that fades the map out under the search row. Top, middle and bottom stops. */
    val VeilTop = Color(0xF5FDFFDC)
    val VeilMiddle = Color(0xE6FDFFDC)
    val VeilBottom = Color(0x00FDFFDC)
}
