package ch.ciuin.app.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.ciuin.app.ui.characters.AlertScene
import ch.ciuin.app.ui.characters.AllSetScene
import ch.ciuin.app.ui.characters.Duck
import ch.ciuin.app.ui.characters.DuckMotion
import ch.ciuin.app.ui.characters.PondScene
import ch.ciuin.app.ui.characters.SharedScene
import ch.ciuin.app.ui.characters.Sheep
import ch.ciuin.app.ui.characters.SheepPose
import ch.ciuin.app.ui.characters.WelcomeScene
import ch.ciuin.app.ui.components.AllowedPill
import ch.ciuin.app.ui.components.AttributionPlate
import ch.ciuin.app.ui.components.BarRow
import ch.ciuin.app.ui.components.BigReading
import ch.ciuin.app.ui.components.BottomSheetSurface
import ch.ciuin.app.ui.components.CenteredHeading
import ch.ciuin.app.ui.components.CharacterTile
import ch.ciuin.app.ui.components.ChecklistItem
import ch.ciuin.app.ui.components.ChoiceButton
import ch.ciuin.app.ui.components.CiuinSwitch
import ch.ciuin.app.ui.components.CiuinTab
import ch.ciuin.app.ui.components.CiuinTabBar
import ch.ciuin.app.ui.components.CiuinTopBar
import ch.ciuin.app.ui.components.DestinationDot
import ch.ciuin.app.ui.components.DestructiveButton
import ch.ciuin.app.ui.components.DestructiveRow
import ch.ciuin.app.ui.components.DimensionRow
import ch.ciuin.app.ui.components.IconDisc
import ch.ciuin.app.ui.components.IconNote
import ch.ciuin.app.ui.components.InfoTile
import ch.ciuin.app.ui.components.LabelPill
import ch.ciuin.app.ui.components.LabelPillStyle
import ch.ciuin.app.ui.components.LayerChipRow
import ch.ciuin.app.ui.components.Level
import ch.ciuin.app.ui.components.LevelBadge
import ch.ciuin.app.ui.components.LevelGlyph
import ch.ciuin.app.ui.components.LevelGlyphSize
import ch.ciuin.app.ui.components.LevelLegend
import ch.ciuin.app.ui.components.LevelLegendCard
import ch.ciuin.app.ui.components.LevelPill
import ch.ciuin.app.ui.components.ListeningButton
import ch.ciuin.app.ui.components.LivePill
import ch.ciuin.app.ui.components.MapPin
import ch.ciuin.app.ui.components.MeasureRing
import ch.ciuin.app.ui.components.NumberedStep
import ch.ciuin.app.ui.components.OfflineBanner
import ch.ciuin.app.ui.components.PageDots
import ch.ciuin.app.ui.components.PermissionRow
import ch.ciuin.app.ui.components.PlaceResultRow
import ch.ciuin.app.ui.components.PrimaryButton
import ch.ciuin.app.ui.components.QuietPlaceCard
import ch.ciuin.app.ui.components.RadioMark
import ch.ciuin.app.ui.components.RadioRow
import ch.ciuin.app.ui.components.RadioState
import ch.ciuin.app.ui.components.ReadingRow
import ch.ciuin.app.ui.components.RecentRow
import ch.ciuin.app.ui.components.RoundIconButton
import ch.ciuin.app.ui.components.RoundIconButtonStyle
import ch.ciuin.app.ui.components.RouteStepRow
import ch.ciuin.app.ui.components.SearchBar
import ch.ciuin.app.ui.components.SearchField
import ch.ciuin.app.ui.components.SecondaryButton
import ch.ciuin.app.ui.components.SecondaryButtonSize
import ch.ciuin.app.ui.components.SectionLabel
import ch.ciuin.app.ui.components.SegmentedControl
import ch.ciuin.app.ui.components.SelectableCard
import ch.ciuin.app.ui.components.SelectedMapPin
import ch.ciuin.app.ui.components.SettingsRow
import ch.ciuin.app.ui.components.SettingsSwitchRow
import ch.ciuin.app.ui.components.SharedFactRow
import ch.ciuin.app.ui.components.StatTile
import ch.ciuin.app.ui.components.StatTileStyle
import ch.ciuin.app.ui.components.SummaryTile
import ch.ciuin.app.ui.components.TagChip
import ch.ciuin.app.ui.components.TextLinkButton
import ch.ciuin.app.ui.components.TitleBlock
import ch.ciuin.app.ui.components.WalkingBanner
import ch.ciuin.app.ui.components.WoolCard
import ch.ciuin.app.ui.components.WoolCardStyle
import ch.ciuin.app.ui.components.WoolPanel
import ch.ciuin.app.ui.components.YouAreHereDot
import ch.ciuin.app.ui.icons.CiuinIcon
import ch.ciuin.app.ui.icons.CiuinIcons
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinType

/**
 * Everything in the kit on one scrolling page: colours, type, icons, characters, scenes and every
 * component in each of its states. It is a reference, not a screen of the app. The desktop test
 * `FoundationsGalleryTest` renders each section to `composeApp/build/design-shots/` for comparison
 * with the Paper exports.
 *
 * Wrap it in `CiuinTheme`.
 */
@Composable
fun FoundationsGallery(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(CiuinColors.Ground).verticalScroll(rememberScrollState())) {
        for (section in GallerySections) {
            GallerySection(section.title) { section.content() }
        }
    }
}

/** One part of the gallery, so a test can render it on its own. */
class GallerySectionSpec(val title: String, val content: @Composable () -> Unit)

/** The gallery's sections in order. */
val GallerySections: List<GallerySectionSpec> = listOf(
    GallerySectionSpec("Colour") { GalleryColours() },
    GallerySectionSpec("Type") { GalleryType() },
    GallerySectionSpec("Icons") { GalleryIcons() },
    GallerySectionSpec("Characters") { GalleryCharacters() },
    GallerySectionSpec("Scenes") { GalleryScenes() },
    GallerySectionSpec("Buttons") { GalleryButtons() },
    GallerySectionSpec("Levels and chips") { GalleryLevels() },
    GallerySectionSpec("Controls") { GalleryControls() },
    GallerySectionSpec("Rows") { GalleryRows() },
    GallerySectionSpec("Cards and tiles") { GalleryCards() },
    GallerySectionSpec("Bars and ring") { GalleryBars() },
    GallerySectionSpec("Sheets, banners and bars") { GallerySurfaces() },
)

/** A titled block of the gallery with the screen's 24 dp margin. */
@Composable
fun GallerySection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = CiuinType.Headline)
        content()
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = CiuinType.Caption, color = CiuinColors.SecondaryText)
}

@Composable
private fun Group(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Note(label)
        content()
    }
}

// Colour.

private class Swatch(val name: String, val color: Color, val hex: String)

private val Swatches = listOf(
    Swatch("Ground", CiuinColors.Ground, "#FFFFFF"),
    Swatch("Ink", CiuinColors.Ink, "#0A0A0A"),
    Swatch("Wool", CiuinColors.Wool, "#F1EFEC"),
    Swatch("Hoof", CiuinColors.Hoof, "#EDAE2B"),
    Swatch("Calm", CiuinColors.Calm, "#1F8A5B"),
    Swatch("Moderate", CiuinColors.Moderate, "#E8A317"),
    Swatch("Loud", CiuinColors.Loud, "#D93A2B"),
    Swatch("SecondaryText", CiuinColors.SecondaryText, "#5A5A5A"),
    Swatch("Destructive", CiuinColors.Destructive, "#B92C1F"),
    Swatch("Muted", CiuinColors.Muted, "#8A8A8A"),
    Swatch("Disabled", CiuinColors.Disabled, "#BDBDBD"),
    Swatch("Handle", CiuinColors.Handle, "#D6D6D6"),
    Swatch("Tile", CiuinColors.Tile, "#F6F5F3"),
    Swatch("Hairline", CiuinColors.Hairline, "#EFEFEF"),
    Swatch("HairlineStrong", CiuinColors.HairlineStrong, "#E9E9E9"),
    Swatch("Outline", CiuinColors.Outline, "#E0E0E0"),
    Swatch("FloatingOutline", CiuinColors.FloatingOutline, "#E4E4E4"),
    Swatch("RingTrack", CiuinColors.RingTrack, "#ECECEC"),
    Swatch("Pasture", CiuinColors.Pasture, "#E7E4DF"),
    Swatch("Pond", CiuinColors.Pond, "#DCE6EA"),
    Swatch("GroundLine", CiuinColors.GroundLine, "#D9D5D0"),
    Swatch("DottedLine", CiuinColors.DottedLine, "#B9B4AE"),
    Swatch("CalmTint", CiuinColors.CalmTint, "#E4F1EA"),
    Swatch("ModerateTint", CiuinColors.ModerateTint, "#FCF4E3"),
    Swatch("LoudTint", CiuinColors.LoudTint, "#FBE5E2"),
    Swatch("MapGround", CiuinColors.MapGround, "#F3F2F0"),
    Swatch("MapPark", CiuinColors.MapPark, "#E8ECE4"),
    Swatch("MapWater", CiuinColors.MapWater, "#E3EAEE"),
    Swatch("MapRail", CiuinColors.MapRail, "#DAD6D1"),
    Swatch("Scrim", CiuinColors.Scrim, "#0A0A0A 40%"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GalleryColours() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for (swatch in Swatches) {
            Column(Modifier.width(105.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(swatch.color, RoundedCornerShape(16.dp))
                        .border(1.dp, CiuinColors.HairlineStrong, RoundedCornerShape(16.dp)),
                )
                Text(swatch.name, style = CiuinType.Eyebrow, color = CiuinColors.Ink, maxLines = 1)
                Note(swatch.hex)
            }
        }
    }
}

// Type.

private class TypeSample(val name: String, val spec: String, val style: TextStyle, val sample: String)

private val TypeSamples = listOf(
    TypeSample("Hero", "40/44 600 -0.025em", CiuinType.Hero, "Find your quiet."),
    TypeSample("Display", "40/44 600 -0.02em", CiuinType.Display, "Quiet places"),
    TypeSample("ScreenTitle", "32/38 600 -0.02em", CiuinType.ScreenTitle, "Measure"),
    TypeSample("Headline", "28/34 600 -0.01em", CiuinType.Headline, "Calm for you"),
    TypeSample("HeadlineTight", "28/34 600 -0.02em", CiuinType.HeadlineTight, "Only numbers leave"),
    TypeSample("PlaceTitle", "24/30 600 -0.01em", CiuinType.PlaceTitle, "Café du Grütli"),
    TypeSample("SheetTitle", "22/28 600 -0.01em", CiuinType.SheetTitle, "Calm for you"),
    TypeSample("Title", "20/28 600", CiuinType.Title, "Café du Grütli"),
    TypeSample("Wordmark", "20/28 600 -0.01em", CiuinType.Wordmark, "ciúin"),
    TypeSample("StatValue", "20/26 600", CiuinType.StatValue, "2,873"),
    TypeSample("CardTitle", "18/24 600", CiuinType.CardTitle, "Likely fine for you"),
    TypeSample("OptionTitle", "16/24 600", CiuinType.OptionTitle, "A bit of everything"),
    TypeSample("Subtitle", "16/22 600", CiuinType.Subtitle, "Walking to Library"),
    TypeSample("Button", "16/20 500", CiuinType.Button, "Measure here"),
    TypeSample("RowTitle", "16/22 500", CiuinType.RowTitle, "Starting point"),
    TypeSample("TileTitle", "15/22 500", CiuinType.TileTitle, "Nearest quiet spot: Library"),
    TypeSample("ButtonMedium", "15/18 500", CiuinType.ButtonMedium, "I'm okay here"),
    TypeSample("Label", "14/20 500", CiuinType.Label, "Labels and buttons"),
    TypeSample("Chip", "14/18 500", CiuinType.Chip, "For you"),
    TypeSample("StepNumber", "14/18 600", CiuinType.StepNumber, "1 2 3"),
    TypeSample("Eyebrow", "13/18 500", CiuinType.Eyebrow, "Around you · Flon"),
    TypeSample("Pill", "13/16 500", CiuinType.Pill, "Quiet room"),
    TypeSample("TabSelected", "12/16 600", CiuinType.TabSelected, "Map"),
    TypeSample("Tab", "12/16 500", CiuinType.Tab, "Measure"),
    TypeSample("Body", "16/24 400", CiuinType.Body, "Body text reads at arm's length."),
    TypeSample("BodyCompact", "16/22 400", CiuinType.BodyCompact, "Tap any place to see how it feels."),
    TypeSample("BodyMedium", "15/22 400", CiuinType.BodyMedium, "Library is on your right"),
    TypeSample("BodySmall", "14/20 400", CiuinType.BodySmall, "46 dB · dim light · few people"),
    TypeSample("Caption", "13/18 400", CiuinType.Caption, "The smallest size, for captions."),
    TypeSample("Attribution", "11/16 400", CiuinType.Attribution, "© OpenStreetMap contributors"),
)

@Composable
fun GalleryType() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (sample in TypeSamples) {
            Column {
                Note("${sample.name} · ${sample.spec}")
                Text(sample.sample, style = sample.style, maxLines = 1)
            }
        }
    }
}

// Icons.

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GalleryIcons() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for ((name, icon) in CiuinIcons.all) {
            Column(Modifier.width(110.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CiuinIcon(icon, name)
                Text(name, style = CiuinType.Attribution, color = CiuinColors.SecondaryText, maxLines = 1)
            }
        }
    }
}

// Characters.

@Composable
private fun CharacterCard(name: String, note: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(CiuinColors.Wool, RoundedCornerShape(24.dp)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(162.dp), contentAlignment = Alignment.Center) { content() }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = CiuinType.CardTitle)
            Text(note, style = CiuinType.BodySmall, color = CiuinColors.SecondaryText)
        }
    }
}

@Composable
fun GalleryCharacters() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CharacterCard("Resting", "Shown when it is calm here. Breathes slowly while z's drift up.") {
            Sheep(SheepPose.Rest, Modifier.size(160.dp, 146.dp))
        }
        CharacterCard("Awake", "Shown when a place is fine for you. Breathes, blinks and twitches an ear.") {
            Sheep(SheepPose.Awake, Modifier.size(146.dp))
        }
        CharacterCard("Ears up", "Shown above your limit. The ears lift once and then hold still.") {
            Sheep(SheepPose.Alert, Modifier.size(146.dp), haptic = false)
        }
        CharacterCard("Reading", "Shown while learning or measuring. Turns a page and the eyes follow.") {
            Sheep(SheepPose.Reading, Modifier.size(146.dp))
        }
        CharacterCard("Hop", "End of onboarding and after a shared measurement. Plays once.") {
            Sheep(SheepPose.Hop, Modifier.size(146.dp))
        }
        CharacterCard("Duck", "One per shared measurement. Waddles in and wiggles its tail.") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
                Duck(Modifier.size(120.dp))
                Duck(Modifier.size(72.dp), DuckMotion.Bob, phase = 1f / 3f)
            }
        }
    }
}

@Composable
fun GalleryScenes() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Group("WelcomeScene · onboarding 1") { WelcomeScene() }
        Group("AllSetScene · onboarding 5") { AllSetScene() }
        Group("AlertScene · alert") { AlertScene("Too much · 74 dB") }
        Group("SharedScene · measure shared") { SharedScene() }
        Group("PondScene · You") { PondScene(ducks = 4) }
    }
}

// Components.

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GalleryButtons() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Group("PrimaryButton · hugging, full width, with icon, disabled") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton("Measure here", {})
                    TextLinkButton("Not now", {}, Modifier.padding(horizontal = 12.dp), underline = true)
                }
                PrimaryButton("Continue", {}, Modifier.fillMaxWidth())
                PrimaryButton("Take me there", {}, Modifier.fillMaxWidth(), leadingIcon = CiuinIcons.Navigation03)
                PrimaryButton("Share measurement", {}, Modifier.fillMaxWidth(), enabled = false)
            }
        }
        Group("SecondaryButton · Large 56, Medium 52, Small 44, Compact 40") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Directions", {})
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("I'm okay here", {}, Modifier.weight(1f), SecondaryButtonSize.Medium)
                    SecondaryButton("Snooze 15 min", {}, Modifier.weight(1f), SecondaryButtonSize.Medium)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Too much", {}, size = SecondaryButtonSize.Small)
                    SecondaryButton("This is fine", {}, size = SecondaryButtonSize.Small)
                    SecondaryButton("Allow", {}, Modifier.width(92.dp), SecondaryButtonSize.Compact)
                    AllowedPill()
                }
            }
        }
        Group("DestructiveButton and text buttons") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DestructiveButton("Reset", {}, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextLinkButton("Skip", {}, color = CiuinColors.SecondaryText)
                    TextLinkButton("Discard", {})
                    TextLinkButton("Reset", {}, color = CiuinColors.Destructive, style = CiuinType.ButtonMedium)
                    TextLinkButton("Add info", {}, style = CiuinType.Eyebrow, underline = true)
                }
            }
        }
        Group("ChoiceButton · unselected, selected") {
            var choice by remember { mutableIntStateOf(1) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceButton("Too much", choice == 0, { choice = 0 }, Modifier.weight(1f))
                ChoiceButton("This is fine", choice == 1, { choice = 1 }, Modifier.weight(1f))
            }
        }
        Group("RoundIconButton · Plain, Tile, OnInk, Floating; ListeningButton off and on") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundIconButton(CiuinIcons.ArrowLeft01, "Back", {}, style = RoundIconButtonStyle.Plain)
                RoundIconButton(CiuinIcons.Cancel01, "Close", {})
                Box(Modifier.background(CiuinColors.Ink, RoundedCornerShape(24.dp)).padding(8.dp)) {
                    RoundIconButton(CiuinIcons.Cancel01, "Close", {}, style = RoundIconButtonStyle.OnInk)
                }
                RoundIconButton(CiuinIcons.Gps01, "Locate me", {}, style = RoundIconButtonStyle.Floating)
                ListeningButton(false, {})
                ListeningButton(true, {})
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GalleryLevels() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Group("LevelBadge · Regular") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LevelBadge(Level.Calm)
                LevelBadge(Level.Moderate)
                LevelBadge(Level.Loud)
                LevelBadge(Level.NoData, text = "No data yet")
            }
        }
        Group("LevelLegend · Small badges, as under a map sheet") {
            LevelLegend(listOf(Level.Calm to "Calm", Level.Moderate to "Moderate", Level.Loud to "Too much", Level.NoData to "No data"))
        }
        Group("LevelGlyph · Regular and Small in 20 dp lanes") {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (level in Level.entries) LevelGlyph(level, lane = 20.dp)
                Spacer(Modifier.width(12.dp))
                for (level in Level.entries) LevelGlyph(level, size = LevelGlyphSize.Small, lane = 20.dp)
            }
        }
        Group("LevelLegendCard, LevelPill") {
            Row(
                Modifier.fillMaxWidth().background(CiuinColors.Wool, RoundedCornerShape(24.dp)).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                LevelLegendCard()
                LevelPill(Level.Loud, "Too much · 74 dB")
            }
        }
        Group("MapPin and SelectedMapPin, on the map ground") {
            FlowRow(
                Modifier.fillMaxWidth().background(CiuinColors.MapGround, RoundedCornerShape(24.dp)).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MapPin(Level.Calm, "Parc de Mon Repos", {})
                MapPin(Level.Loud, "St-François", {})
                MapPin(Level.Moderate, "Flon", {})
                MapPin(Level.NoData, "Café Romand", {})
                SelectedMapPin(Level.Calm, "Café du Grütli")
                YouAreHereDot()
                DestinationDot(Level.Calm)
                LabelPill("Library", style = LabelPillStyle.Outlined)
            }
        }
        Group("LayerChipRow") {
            var layer by remember { mutableIntStateOf(0) }
            LayerChipRow(listOf("For you", "Sound", "Light", "Crowd", "Access"), layer, { layer = it }, contentPadding = PaddingValues(0.dp))
        }
        Group("TagChip, LabelPill, LivePill") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TagChip("Quiet room")
                TagChip("Outdoor seating")
                TagChip("No music")
                LabelPill("Lausanne")
                LabelPill("New", style = LabelPillStyle.InkSmall)
                LivePill()
            }
        }
    }
}

@Composable
fun GalleryControls() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Group("CiuinSwitch · on, off") {
            var on by remember { mutableStateOf(true) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CiuinSwitch(on, { on = it })
                CiuinSwitch(!on, { on = !it })
            }
        }
        Group("SegmentedControl") {
            var segment by remember { mutableIntStateOf(0) }
            SegmentedControl(listOf("Day", "Night"), segment, { segment = it })
        }
        Group("RadioMark · selected, unselected, unavailable") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RadioMark(RadioState.Selected)
                RadioMark(RadioState.Unselected)
                RadioMark(RadioState.Unavailable)
            }
        }
        Group("PageDots · step 1, 3 and 5 of 5") {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                PageDots(5, 0)
                PageDots(5, 2)
                PageDots(5, 4)
            }
        }
        Group("SearchBar (resting, over the map) and SearchField (focused)") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().background(CiuinColors.MapGround, RoundedCornerShape(24.dp)).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SearchBar({}, Modifier.weight(1f))
                    ListeningButton(true, {})
                }
                var query by remember { mutableStateOf("caf") }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SearchField(query, { query = it }, Modifier.weight(1f))
                    TextLinkButton("Cancel", {})
                }
            }
        }
    }
}

@Composable
fun GalleryRows() {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Group("SettingsRow, SettingsSwitchRow, DestructiveRow · You") {
            Column {
                var listen by remember { mutableStateOf(true) }
                var reduce by remember { mutableStateOf(false) }
                SettingsRow("Starting point", "A bit of everything", {})
                SettingsSwitchRow("Listen in the background", "Wakes only when it gets loud", listen, { listen = it })
                SettingsSwitchRow("Reduce motion", "Sheep and ducks hold still", reduce, { reduce = it })
                DestructiveRow("Reset what the sheep learned", {})
            }
        }
        Group("PermissionRow · onboarding 4") {
            Column {
                PermissionRow(CiuinIcons.Mic01, "Microphone", "Loudness, on this phone.", granted = true, onAllow = {})
                PermissionRow(CiuinIcons.Camera01, "Camera", "Light, flicker, crowding.", granted = false, onAllow = {}, divider = false)
            }
        }
        Group("PlaceResultRow, RecentRow · search") {
            Column {
                SectionLabel("Matches")
                PlaceResultRow(Level.Calm, "Café du Grütli", "Café · 80 m · open now", {})
                PlaceResultRow(Level.Moderate, "Café de Grancy", "Café · 450 m · open now", {})
                PlaceResultRow(Level.Loud, "Cafétéria Flon", "Cafeteria · 200 m · open now", {}, levelWord = "Too much")
                PlaceResultRow(Level.NoData, "Café Romand", "Restaurant · 600 m · closed", {}, divider = false)
                RecentRow("Library", {}, divider = false)
            }
        }
        Group("DimensionRow · place sheet") {
            Column {
                DimensionRow(Level.Moderate, "Sound", "Moderate", detail = "58 dB measured")
                DimensionRow(Level.Calm, "Light", "Dim", detail = "80 lux · no flicker")
                DimensionRow(Level.NoData, "Access", "No data yet", actionLabel = "Add info", divider = false)
            }
        }
        Group("ReadingRow · measure review") {
            Column {
                ReadingRow(Level.Moderate, "Sound · Moderate", "Mostly people talking", "57 dB", valueNote = "peak 71")
                ReadingRow(Level.Calm, "Light · Dim", "No flicker found", "82 lux", divider = false)
            }
        }
        Group("RouteStepRow · route") {
            Column {
                RouteStepRow(CiuinIcons.ArrowLeft01, "Head west on Rue de Genève", "80 m", current = true)
                RouteStepRow(CiuinIcons.Location01, "Library is on your right", "40 m", iconSize = 22.dp, divider = false)
            }
        }
        Group("RadioRow · model size") {
            Column {
                RadioRow("Small", "1.2 MB · lightest on the battery", RadioState.Selected, {})
                RadioRow("Medium", "1.9 MB · for stronger phones", RadioState.Unselected, {})
                RadioRow("Large", "Not available yet", RadioState.Unavailable, {}, divider = false)
            }
        }
        Group("SharedFactRow · privacy") {
            Column {
                SharedFactRow("Average and peak loudness", shared = true)
                SharedFactRow("Audio and camera frames", shared = false, divider = false)
            }
        }
        Group("NumberedStep, ChecklistItem, IconNote") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                NumberedStep(1, "Tap any place to see how it feels.")
                NumberedStep(2, "Tell the sheep when it's too much.")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChecklistItem("Hold the phone steady")
                    ChecklistItem("Aim the camera at the room")
                }
                IconNote(CiuinIcons.SquareLock02, "Audio and video stay on this phone.")
            }
        }
    }
}

@Composable
fun GalleryCards() {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Group("SelectableCard · with icon (onboarding 3), without (measure start)") {
            var preset by remember { mutableIntStateOf(0) }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectableCard("A bit of everything", "The autism-friendly mix.", preset == 0, { preset = 0 }, leadingIcon = CiuinIcons.SlidersHorizontal)
                SelectableCard("Sound", "Noise and sharp peaks.", preset == 1, { preset = 1 }, leadingIcon = CiuinIcons.VolumeHigh)
                SelectableCard("Café du Grütli", "Café · 10 m · 4 measurements", preset == 0, { preset = 0 })
                SelectableCard("Somewhere else", "Measure this spot without a place", preset == 1, { preset = 1 })
            }
        }
        Group("WoolCard · Privacy, Verdict, HowTo, Reward, Note") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WoolCard(WoolCardStyle.Privacy) {
                    Sheep(SheepPose.Rest, Modifier.size(96.dp, 88.dp), contentDescription = null, zzz = false)
                    TitleBlock(
                        title = "Only numbers are shared",
                        modifier = Modifier.weight(1f),
                        subtitle = "Ciúin sends decibels, lux and a people count. Audio, pictures and your taps stay here.",
                        titleStyle = CiuinType.Subtitle,
                        gap = 4.dp,
                    )
                }
                WoolCard(WoolCardStyle.Verdict) {
                    Sheep(SheepPose.Awake, Modifier.size(60.dp), contentDescription = null)
                    TitleBlock(
                        title = "Likely fine for you",
                        modifier = Modifier.weight(1f),
                        subtitle = "Low overload risk · 4 measurements",
                        titleStyle = CiuinType.CardTitle,
                        subtitleStyle = CiuinType.Caption,
                    )
                }
                WoolCard(WoolCardStyle.HowTo) {
                    Sheep(SheepPose.Reading, Modifier.size(72.dp), contentDescription = null)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChecklistItem("Hold the phone steady")
                        ChecklistItem("Aim the camera at the room")
                        ChecklistItem("Audio and video stay here")
                    }
                }
                WoolCard(WoolCardStyle.Reward) {
                    Duck(Modifier.size(64.dp), contentDescription = null)
                    TitleBlock(
                        title = "Share it and a duck joins the pond",
                        modifier = Modifier.weight(1f),
                        subtitle = "This will be measurement 5 here. Only the numbers above are sent.",
                        titleStyle = CiuinType.Subtitle,
                        subtitleStyle = CiuinType.Caption,
                    )
                }
                WoolCard(WoolCardStyle.Note) {
                    Sheep(SheepPose.Rest, Modifier.size(72.dp, 66.dp), contentDescription = null, zzz = false)
                    Text("The server rejects anything that is not on the first list.", Modifier.weight(1f), style = CiuinType.BodySmall)
                }
            }
        }
        Group("WoolPanel with IconDisc and Inner StatTiles · model") {
            WoolPanel {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconDisc(CiuinIcons.Chip, size = 44.dp, iconSize = 24.dp, color = CiuinColors.Ground)
                    TitleBlock("Student S", subtitle = "Chosen for this phone", titleStyle = CiuinType.CardTitle, subtitleStyle = CiuinType.Caption, gap = 0.dp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("1.17 MB", "Size", Modifier.weight(1f), StatTileStyle.Inner)
                    StatTile("72%", "Accuracy", Modifier.weight(1f), StatTileStyle.Inner)
                    StatTile("68k", "Weights", Modifier.weight(1f), StatTileStyle.Inner)
                }
            }
        }
        Group("StatTile · ValueFirst (globe), LabelFirst (measuring)") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("2,873", "places", Modifier.weight(1f))
                    StatTile("148", "readings", Modifier.weight(1f))
                    StatTile("143", "tags", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("57 dB", "Sound", Modifier.weight(1f), StatTileStyle.LabelFirst)
                    StatTile("82 lux", "Light", Modifier.weight(1f), StatTileStyle.LabelFirst)
                    StatTile("3", "People", Modifier.weight(1f), StatTileStyle.LabelFirst)
                }
            }
        }
        Group("InfoTile, SummaryTile, QuietPlaceCard") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoTile("Nearest quiet spot: Library", "120 m · 2 min walk · open now", onClick = {}) { LevelGlyph(Level.Calm) }
                InfoTile("2 measurements waiting", "They send when you're back online") { CiuinIcon(CiuinIcons.Clock01, null) }
                SummaryTile("57 dB · 82 lux · 3 people")
                QuietPlaceCard(Level.Calm, "Library · Calm", "120 m · 2 min walk · open now", "41 dB") {}
            }
        }
        Group("CharacterTile with TitleBlock, BigReading, IconDisc") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CharacterTile { Sheep(SheepPose.Rest, Modifier.size(62.dp, 56.dp), contentDescription = null) }
                    TitleBlock("Calm for you", Modifier.weight(1f), eyebrow = "Around you · Flon", subtitle = "46 dB · dim light · few people")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconDisc(CiuinIcons.Wheelchair)
                    TitleBlock("63% wheelchair accessible", Modifier.weight(1f), subtitle = "From 521 tagged places")
                }
                BigReading("60 dB", "Moderate")
            }
        }
    }
}

@Composable
fun GalleryBars() {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Group("BarRow · sound sources, access, listening") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BarRow("Road", 0.78f, "62 dB", labelWidth = 48.dp, valueWidth = 48.dp, gap = 12.dp)
                BarRow("Rail", 0.52f, "55 dB", labelWidth = 48.dp, valueWidth = 48.dp, gap = 12.dp)
                BarRow("Accessible", 0.63f, "63%") { LevelGlyph(Level.Calm, size = LevelGlyphSize.Small, lane = 16.dp) }
                BarRow("Limited", 0.18f, "18%") { LevelGlyph(Level.Moderate, size = LevelGlyphSize.Small, lane = 16.dp) }
                BarRow("Asleep, no model", 0.82f, "82%", labelWidth = 150.dp)
                BarRow("Large model, camera", 0.03f, "3%", labelWidth = 150.dp)
            }
        }
        Group("MeasureRing · 40% through") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                MeasureRing(0.4f) { Sheep(SheepPose.Reading, Modifier.size(156.dp), contentDescription = null) }
            }
        }
    }
}

@Composable
fun GallerySurfaces() {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Group("CiuinTopBar · wordmark, back with Skip, back with title; CenteredHeading") {
            Column {
                CiuinTopBar(wordmark = true, trailing = { TextLinkButton("Skip", {}, color = CiuinColors.SecondaryText, textAlign = TextAlign.End) })
                CiuinTopBar(onBack = {}, trailing = { TextLinkButton("Skip", {}, color = CiuinColors.SecondaryText, textAlign = TextAlign.End) })
                CiuinTopBar(title = "On this phone", onBack = {})
                CenteredHeading("Measuring", "Café du Grütli")
            }
        }
        Group("OfflineBanner, WalkingBanner") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OfflineBanner({})
                WalkingBanner("Walking to Library", "120 m · 2 min", {})
            }
        }
        Group("BottomSheetSurface over the map, with AttributionPlate") {
            Column(Modifier.fillMaxWidth().background(CiuinColors.MapGround)) {
                Box(Modifier.padding(start = 16.dp, top = 40.dp, bottom = 8.dp)) {
                    AttributionPlate("© OpenStreetMap · noise: sonBASE © FOEN")
                }
                BottomSheetSurface {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        CharacterTile { Sheep(SheepPose.Rest, Modifier.size(62.dp, 56.dp), contentDescription = null, zzz = false) }
                        TitleBlock("Calm for you", Modifier.weight(1f), eyebrow = "Around you · Flon", subtitle = "46 dB · dim light · few people")
                    }
                    LevelLegend(listOf(Level.Calm to "Calm", Level.Moderate to "Moderate", Level.Loud to "Too much", Level.NoData to "No data"))
                }
                var tab by remember { mutableStateOf(CiuinTab.Map) }
                CiuinTabBar(tab, { tab = it })
            }
        }
    }
}
