package ch.ciuin.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 *
 * Dark mode swaps the ground and the ink: cream type on a deep slate ground, darker mist cards, and
 * the three levels lifted a little so they keep about the same contrast on the dark ground. The
 * characters keep their own colours. Each role below gives the light value first, then the dark one.
 */
object CiuinColors {
    /**
     * Whether the dark palette is showing. [CiuinTheme] sets it; every colour below reads it, so a
     * change redraws whatever used that colour, in composition or in drawing alike.
     */
    var dark: Boolean by mutableStateOf(false)
        internal set

    private fun pick(light: Color, dark: Color) = if (this.dark) dark else light

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
    val Ground: Color get() = pick(Cream, Color(0xFF1F2230))

    /** Text, icons, the primary button, selected chips and the dark banner. */
    val Ink: Color get() = pick(Slate, Color(0xFFF1F3D8))

    /**
     * Mist blue for character cards, icon discs, the "Allowed" pill, the segmented control. Lighter
     * than the swatch so that [SecondaryText] still reads at 4.5:1 on it.
     */
    val Wool: Color get() = pick(Color(0xFFDDE2E9), Color(0xFF2D3242))

    /** The calm accent: the measuring waveform and other quiet highlights. Decorative only, not for text. */
    val Accent: Color get() = pick(Sage, Color(0xFF9EB0AC))

    /** The sheep's hoof yellow, kept for the characters. */
    val Hoof: Color get() = pick(Color(0xFFEDAE2B), Color(0xFFEDAE2B))

    /** Calm level, a deep sage: always drawn as a circle. */
    val Calm: Color get() = pick(Color(0xFF4F8580), Color(0xFF6FA8A2))

    /** Moderate level, a muted ochre: always drawn as a diamond. */
    val Moderate: Color get() = pick(Color(0xFFB38A2E), Color(0xFFD1A64A))

    /** Loud or too much, a dusty terracotta: always drawn as a square. */
    val Loud: Color get() = pick(Color(0xFFA04A34), Color(0xFFD0765E))

    /** Supporting text: 5.9:1 on [Ground], 4.7:1 on [Wool]. */
    val SecondaryText: Color get() = pick(Color(0xFF5E6273), Color(0xFFB4B8C6))

    // Text and marks.
    /** Text and icons on [Ink]. */
    val OnInk: Color get() = Ground

    /** Second line of text on [Ink] (offline banner, walking header); 5:1. */
    val OnInkSecondary: Color get() = pick(Mist, Color(0xFF5E6273))

    /** Destructive text and outline: "Reset what the sheep learned", the Reset button. */
    val Destructive: Color get() = pick(Color(0xFF8E3B2A), Color(0xFFE08A72))

    /** The dashed no-data ring, the empty radio ring, the title of an unavailable option, the sheep's z's. */
    val Muted: Color get() = pick(Color(0xFF8A8F9E), Color(0xFF8A8FA0))

    /** Switch track when off and the dashed ring of an unavailable radio. */
    val Disabled: Color get() = pick(Color(0xFFBCC0C8), Color(0xFF4A4F60))

    // Surfaces and lines.
    /** Quiet mist tile: stat tiles, tag chips, the nearest-spot card, the round close button, the Live pill. */
    val Tile: Color get() = pick(Color(0xFFE5E8ED), Color(0xFF282C3B))

    /** Hairline between list rows, and the track of a progress bar. */
    val Hairline: Color get() = pick(SoftGrey, Color(0xFF353A4A))

    /** Slightly stronger hairline: permission rows, the tab bar's top edge, the ground colour swatch. */
    val HairlineStrong: Color get() = pick(Color(0xFFD3D4CF), Color(0xFF3A3F50))

    /** Border of an unselected chip or selectable card. */
    val Outline: Color get() = pick(Color(0xFFCDCFCB), Color(0xFF454A5B))

    /** Border of cream controls that float over the map: search field, pins, locate button, the quiet-place card. */
    val FloatingOutline: Color get() = pick(Color(0xFFD6D8D0), Color(0xFF3F4455))

    /** Drag handle of a sheet and the page dots not yet reached. */
    val Handle: Color get() = pick(Color(0xFFC9CDD4), Color(0xFF4C5163))

    /** Track of the measure ring and the quiet bars of the waveform. */
    val RingTrack: Color get() = pick(Color(0xFFE6E8D2), Color(0xFF33384A))

    /** Dimmed backdrop behind a confirmation sheet: slate at 40%. */
    val Scrim: Color get() = pick(Color(0x66505569), Color(0x99101218))

    /** Plate behind the map attribution: cream at 85%. */
    val AttributionPlate: Color get() = pick(Color(0xD9FDFFDC), Color(0xD91F2230))

    // Character scenes.
    /** The ground curve the sheep stands on: cream with a little sage. */
    val Pasture: Color get() = pick(Color(0xFFE5EBD0), Color(0xFF293130))

    /** The duck pond. */
    val Pond: Color get() = pick(Mist, Color(0xFF3A4256))

    /** Ground line and the hop's own shadow on the Motion board. */
    val GroundLine: Color get() = pick(Color(0xFFD5D9C4), Color(0xFF3A403F))

    /** Dotted hop arc, and the dashed outline of a hexagon with no data. */
    val DottedLine: Color get() = pick(Color(0xFFB4B8A8), Color(0xFF6A6F66))

    // Level tints: the disc behind a level glyph in a map pin, each level at 14% over [Ground].
    val CalmTint: Color get() = pick(Color(0xFFE5EECF), Color(0xFF2A3540))
    val ModerateTint: Color get() = pick(Color(0xFFF3EFC4), Color(0xFF383433))
    val LoudTint: Color get() = pick(Color(0xFFF0E6C4), Color(0xFF382E36))

    // The map itself: cream land, sage parks, mist water.
    val MapGround: Color get() = pick(Color(0xFFF4F6D6), Color(0xFF23262F))
    val MapPark: Color get() = pick(Color(0xFFE8EED1), Color(0xFF252D29))
    val MapWater: Color get() = pick(Color(0xFFE1E6DE), Color(0xFF232A36))
    val MapRoad: Color get() = pick(Color(0xFFFEFFEE), Color(0xFF2E3240))
    val MapRail: Color get() = pick(Color(0xFFD3D5C6), Color(0xFF3A3E48))

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
    val LocationHalo: Color get() = pick(Color(0x1A505569), Color(0x1AF1F3D8))

    /** Cream veil that fades the map out under the search row. Top, middle and bottom stops. */
    val VeilTop: Color get() = pick(Color(0xF5FDFFDC), Color(0xF51F2230))
    val VeilMiddle: Color get() = pick(Color(0xE6FDFFDC), Color(0xE61F2230))
    val VeilBottom: Color get() = pick(Color(0x00FDFFDC), Color(0x001F2230))
}
