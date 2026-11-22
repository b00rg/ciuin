package ch.ciuin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinElevation
import ch.ciuin.app.ui.theme.CiuinRadii
import ch.ciuin.app.ui.theme.CiuinType
import ch.ciuin.app.ui.theme.ciuinShadow
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * How a place or area reads on one sense. Every level has a colour, a shape and a word, so the map
 * still reads without colour: calm is a green circle, moderate an amber diamond, loud a red square,
 * and no data a dashed grey ring.
 *
 * [word] is the default; the design also says "Too much", "Quiet", "Dim", "Bright", "Accessible"
 * and so on for the same three shapes, so the components take the word as a parameter.
 */
enum class Level(val word: String, val color: Color, val tint: Color) {
    Calm("Calm", CiuinColors.Calm, CiuinColors.CalmTint),
    Moderate("Moderate", CiuinColors.Moderate, CiuinColors.ModerateTint),
    Loud("Loud", CiuinColors.Loud, CiuinColors.LoudTint),
    NoData("No data", CiuinColors.Muted, CiuinColors.Tile),
}

/** The two sizes the glyphs are drawn at. The shapes differ in side so they look equally heavy. */
enum class LevelGlyphSize(internal val circle: Dp, internal val diamond: Dp, internal val square: Dp) {
    /** Next to 14 to 16 dp text: rows, badges, the selected pin. Circle 14, diamond 11, square 13. */
    Regular(14.dp, 11.dp, 13.dp),

    /** Next to 13 dp text: sheet legends, map pins, the access bars. Circle 12, diamond 10, square 11. */
    Small(12.dp, 10.dp, 11.dp),
}

/**
 * The shape of a [level], alone. Used wherever a level is marked: legends, list rows, pins, bars.
 *
 * With a [lane], the glyph is centred in a square of that width so glyphs of different shapes line
 * up down a list (16 in legend cards and bar rows, 20 in list rows, 24 in tiles). Without one it
 * takes the size of its own shape; the diamond then overhangs its box by its rotated corners, as it
 * does on the artboards.
 *
 * The glyph is silent to screen readers because a word normally sits beside it. Where it stands
 * alone (the nearest-spot tile), pass the level's word as [contentDescription].
 */
@Composable
fun LevelGlyph(
    level: Level,
    modifier: Modifier = Modifier,
    size: LevelGlyphSize = LevelGlyphSize.Regular,
    lane: Dp = Dp.Unspecified,
    contentDescription: String? = null,
) {
    val side = when (level) {
        Level.Calm, Level.NoData -> size.circle
        Level.Moderate -> size.diamond
        Level.Loud -> size.square
    }
    val described = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    val glyph = described.size(side).drawBehind {
        val corner = CornerRadius(CiuinRadii.Glyph.toPx())
        when (level) {
            Level.Calm -> drawCircle(level.color)
            Level.Moderate -> rotate(45f) { drawRoundRect(level.color, cornerRadius = corner) }
            Level.Loud -> drawRoundRect(level.color, cornerRadius = corner)
            Level.NoData -> drawDashedRing(level.color)
        }
    }
    if (lane.isSpecified) {
        Box(modifier.size(lane), contentAlignment = Alignment.Center) { Box(glyph) }
    } else {
        Box(modifier.then(glyph))
    }
}

/**
 * A dashed ring 1.5 dp wide, drawn just inside the bounds: the no-data glyph and the radio mark of
 * an option that is not available. Dashes are twice the line width and spaced to close evenly, which
 * gives the nine dashes of a 14 dp ring on the artboards.
 */
internal fun DrawScope.drawDashedRing(color: Color) {
    val width = 1.5.dp.toPx()
    val radius = (size.minDimension - width) / 2
    val circumference = (2 * PI * radius).toFloat()
    val dash = width * 1.9f
    val count = (circumference / (width * 3.07f)).roundToInt().coerceAtLeast(4)
    val gap = circumference / count - dash
    drawCircle(
        color = color,
        radius = radius,
        style = Stroke(width = width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), dash / 2)),
    )
}

/** The two sizes of a glyph with its word. */
enum class LevelBadgeSize { Regular, Small }

/**
 * A level as a glyph and its word, side by side: the legend under every map sheet and the "Level
 * badges" row on the Foundations board.
 *
 * [LevelBadgeSize.Regular] is 14/20 text with an 8 dp gap; [LevelBadgeSize.Small], used in sheets,
 * is 13/18 text with a 6 dp gap (7 after the diamond). The word of [Level.NoData] is grey.
 */
@Composable
fun LevelBadge(
    level: Level,
    modifier: Modifier = Modifier,
    text: String = level.word,
    size: LevelBadgeSize = LevelBadgeSize.Regular,
) {
    val small = size == LevelBadgeSize.Small
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        // The small diamond overhangs its 10 dp box, so the artboards give it one more dp of air.
        horizontalArrangement = Arrangement.spacedBy(
            when {
                !small -> 8.dp
                level == Level.Moderate -> 7.dp
                else -> 6.dp
            },
        ),
    ) {
        LevelGlyph(level, size = if (small) LevelGlyphSize.Small else LevelGlyphSize.Regular)
        Label(
            text = text,
            style = if (small) CiuinType.Eyebrow else CiuinType.Label,
            color = if (level == Level.NoData) CiuinColors.SecondaryText else CiuinColors.Ink,
            maxLines = 1,
        )
    }
}

/**
 * The legend under a map sheet: [LevelBadgeSize.Small] badges 16 dp apart, one per entry.
 * "Calm, Moderate, Too much, No data" on For you; "Quiet, under 50 / Moderate / Loud, over 65" on Sound.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LevelLegend(entries: List<Pair<Level, String>>, modifier: Modifier = Modifier) {
    // On one line at the artboard's width; on a narrower phone whole badges move to a second line.
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        for ((level, word) in entries) LevelBadge(level, text = word, size = LevelBadgeSize.Small)
    }
}

/**
 * The white card that explains the three levels, stacked: on the second onboarding screen, over the
 * honeycomb. Glyphs sit in a 16 dp lane so the words line up.
 */
@Composable
fun LevelLegendCard(
    modifier: Modifier = Modifier,
    entries: List<Pair<Level, String>> = listOf(Level.Calm, Level.Moderate, Level.Loud).map { it to it.word },
) {
    Column(
        modifier = modifier
            .background(CiuinColors.Ground, RoundedCornerShape(CiuinRadii.Option))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for ((level, word) in entries) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LevelGlyph(level, lane = 16.dp)
                Label(word, CiuinType.Label)
            }
        }
    }
}

/**
 * The level pill that marks a place on the map: white, 36 high, with the glyph on a tinted disc and
 * the place name. "Parc de Mon Repos", "Library", "St-François" on the map home.
 */
@Composable
fun MapPin(
    level: Level,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    levelWord: String = level.word,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) { contentDescription = "$text, $levelWord" }
            .pressable(onClick)
            .ciuinShadow(CiuinElevation.Pin, shape)
            .height(36.dp)
            .background(CiuinColors.Ground, shape)
            .border(1.dp, CiuinColors.FloatingOutline, shape)
            .padding(start = 7.dp, end = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(24.dp).background(level.tint, CircleShape), contentAlignment = Alignment.Center) {
            LevelGlyph(level, size = LevelGlyphSize.Small)
        }
        Label(text, CiuinType.Eyebrow, Modifier.clearAndSetSemantics {})
    }
}

/**
 * The pin of the place whose sheet is open: black, 44 high, with a pointer underneath and the glyph
 * on a white disc. "Café du Grütli" above the place sheet. Its tip is the bottom centre.
 */
@Composable
fun SelectedMapPin(
    level: Level,
    text: String,
    modifier: Modifier = Modifier,
    levelWord: String = level.word,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "$text, $levelWord, selected" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .ciuinShadow(CiuinElevation.SelectedPin, shape)
                .height(44.dp)
                .background(CiuinColors.Ink, shape)
                .padding(start = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(28.dp).background(CiuinColors.Ground, CircleShape), contentAlignment = Alignment.Center) {
                LevelGlyph(level)
            }
            Label(text, CiuinType.Label, Modifier.clearAndSetSemantics {}, color = CiuinColors.OnInk)
        }
        // The pointer: a 12 dp square turned 45 degrees and tucked 7 dp up into the pill.
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val pointer = measurable.measure(constraints)
                    val tuck = 7.dp.roundToPx()
                    layout(pointer.width, pointer.height - tuck) { pointer.place(0, -tuck) }
                }
                .size(12.dp)
                .drawBehind {
                    rotate(45f) { drawRoundRect(CiuinColors.Ink, cornerRadius = CornerRadius(CiuinRadii.Glyph.toPx())) }
                },
        )
    }
}
