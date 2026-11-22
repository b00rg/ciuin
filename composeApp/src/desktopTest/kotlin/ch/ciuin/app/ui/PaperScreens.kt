package ch.ciuin.app.ui

import ch.ciuin.app.ui.you.learnedFromText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.ciuin.app.ui.characters.AlertScene
import ch.ciuin.app.ui.characters.AllSetScene
import ch.ciuin.app.ui.characters.Duck
import ch.ciuin.app.ui.characters.LayerCharacter
import ch.ciuin.app.ui.characters.LayerSheep
import ch.ciuin.app.ui.characters.SharedScene
import ch.ciuin.app.ui.characters.Sheep
import ch.ciuin.app.ui.characters.SheepPose
import ch.ciuin.app.ui.characters.WelcomeScene
import ch.ciuin.app.ui.components.AttributionPlate
import ch.ciuin.app.ui.components.BarRow
import ch.ciuin.app.ui.components.BigReading
import ch.ciuin.app.ui.components.BottomSheetSurface
import ch.ciuin.app.ui.components.CenteredHeading
import ch.ciuin.app.ui.components.CharacterTile
import ch.ciuin.app.ui.components.ChecklistItem
import ch.ciuin.app.ui.components.ChoiceButton
import ch.ciuin.app.ui.components.CiuinTab
import ch.ciuin.app.ui.components.CiuinTabBar
import ch.ciuin.app.ui.components.CiuinTopBar
import ch.ciuin.app.ui.components.DestructiveButton
import ch.ciuin.app.ui.components.DestructiveRow
import ch.ciuin.app.ui.components.DimensionRow
import ch.ciuin.app.ui.components.IconDisc
import ch.ciuin.app.ui.components.IconNote
import ch.ciuin.app.ui.components.InfoTile
import ch.ciuin.app.ui.components.LayerChipRow
import ch.ciuin.app.ui.components.Level
import ch.ciuin.app.ui.components.LevelGlyph
import ch.ciuin.app.ui.components.LevelGlyphSize
import ch.ciuin.app.ui.components.LevelLegend
import ch.ciuin.app.ui.components.LinkRow
import ch.ciuin.app.ui.components.ListeningButton
import ch.ciuin.app.ui.components.LivePill
import ch.ciuin.app.ui.components.MeasureRing
import ch.ciuin.app.ui.components.NumberedStep
import ch.ciuin.app.ui.components.OfflineBanner
import ch.ciuin.app.ui.components.PageDots
import ch.ciuin.app.ui.components.PermissionRow
import ch.ciuin.app.ui.components.PlaceResultRow
import ch.ciuin.app.ui.components.PrimaryButton
import ch.ciuin.app.ui.components.QuietPlaceCard
import ch.ciuin.app.ui.components.RadioRow
import ch.ciuin.app.ui.components.RadioState
import ch.ciuin.app.ui.components.ReadingRow
import ch.ciuin.app.ui.components.RecentRow
import ch.ciuin.app.ui.components.RoundIconButton
import ch.ciuin.app.ui.components.RouteStepRow
import ch.ciuin.app.ui.components.SearchBar
import ch.ciuin.app.ui.components.SearchField
import ch.ciuin.app.ui.components.SecondaryButton
import ch.ciuin.app.ui.components.SecondaryButtonSize
import ch.ciuin.app.ui.components.SectionLabel
import ch.ciuin.app.ui.components.SegmentedControl
import ch.ciuin.app.ui.components.SelectableCard
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
import ch.ciuin.app.ui.icons.CiuinIcon
import ch.ciuin.app.ui.icons.CiuinIcons
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinType

/*
 * The Paper artboards rebuilt from the kit and nothing else, with the artboards' own copy. They are
 * not the app's screens: they have no state and no map. They exist so `PaperScreensTest` can lay the
 * kit over the Paper exports, and as worked examples of how the components go together.
 *
 * The artboards draw a 62 dp status bar on top and a home indicator at the bottom. Both are device
 * chrome, so these leave that space empty.
 */

internal val StatusBar = 62.dp

/** The home indicator's strip inside a footer: 14 high on most artboards. */
private val HomeIndicator = 14.dp

@Composable
private fun Screen(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = StatusBar), content = content)
}

/** The scrolling part of a screen: the 24 dp margins and the artboard's gap between blocks. */
@Composable
private fun ColumnScope.Content(top: Dp = 8.dp, gap: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = top),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

@Composable
private fun Footer(gap: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        content()
        Spacer(Modifier.height(HomeIndicator))
    }
}

@Composable
private fun Skip() {
    TextLinkButton("Skip", {}, color = CiuinColors.SecondaryText, textAlign = TextAlign.End)
}

@Composable
private fun Copy(title: String, body: String?, gap: Dp = 10.dp, hero: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        Text(title, style = if (hero) CiuinType.Hero else CiuinType.ScreenTitle)
        if (body != null) Text(body, style = CiuinType.Body, color = CiuinColors.SecondaryText)
    }
}

@Composable
internal fun Onboarding1() = Screen {
    Content(gap = 28.dp) {
        CiuinTopBar(wordmark = true, trailing = { Skip() })
        WelcomeScene()
        Copy(
            title = "Find your quiet.",
            body = "Ciúin maps how loud, bright and busy places are, so you can choose where to go before you get there.",
            gap = 12.dp,
            hero = true,
        )
    }
    Footer {
        PageDots(5, 0)
        PrimaryButton("Get started", {}, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun Onboarding3() = Screen {
    Content {
        CiuinTopBar(onBack = {}, trailing = { Skip() })
        Copy("What bothers you most?", "Pick the closest fit. Your map starts here and keeps learning from you.")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectableCard("A bit of everything", "The autism-friendly mix.", true, {}, leadingIcon = CiuinIcons.SlidersHorizontal)
            SelectableCard("Sound", "Noise and sharp peaks.", false, {}, leadingIcon = CiuinIcons.VolumeHigh)
            SelectableCard("Light", "Brightness and flicker.", false, {}, leadingIcon = CiuinIcons.Sun03)
            SelectableCard("Crowds", "Busy, crowded places.", false, {}, leadingIcon = CiuinIcons.UserGroup)
        }
    }
    Footer {
        Text("You can change this any time in You.", style = CiuinType.BodySmall, color = CiuinColors.SecondaryText)
        PageDots(5, 2)
        PrimaryButton("Continue", {}, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun Onboarding4() = Screen {
    Content {
        CiuinTopBar(onBack = {}, trailing = { Skip() })
        Text("Measuring happens on your phone.", style = CiuinType.ScreenTitle)
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
        Column {
            PermissionRow(CiuinIcons.Mic01, "Microphone", "Loudness, on this phone.", granted = true, onAllow = {})
            PermissionRow(CiuinIcons.Camera01, "Camera", "Light, flicker, crowding.", granted = false, onAllow = {})
            PermissionRow(CiuinIcons.Location01, "Location", "Quiet places near you.", granted = false, onAllow = {})
            PermissionRow(CiuinIcons.Notification02, "Gentle alerts", "A nudge before overload.", granted = false, onAllow = {}, divider = false)
        }
    }
    Footer {
        PageDots(5, 3)
        PrimaryButton("Continue", {}, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun Onboarding5() = Screen {
    Content(gap = 24.dp) {
        CiuinTopBar(onBack = {})
        AllSetScene()
        Copy("You're all set.", "Three things to know before you go.", hero = true)
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            NumberedStep(1, "Tap any place to see how it feels.")
            NumberedStep(2, "Tell the sheep when it's too much.")
            NumberedStep(3, "Measure a place to help others find calm.")
        }
    }
    Footer {
        PageDots(5, 4)
        PrimaryButton("Open the map", {}, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun Globe() = Screen {
    Content(gap = 16.dp) {
        CiuinTopBar(wordmark = true, trailing = { LivePill() })
        Spacer(Modifier.height(324.dp))
        Copy("Every spike is a place someone measured.", "Drag to spin the globe. Lausanne has the most detail so far.", gap = 8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("2,873", "places", Modifier.weight(1f))
            StatTile("148", "readings", Modifier.weight(1f))
            StatTile("859", "quiet spots", Modifier.weight(1f))
        }
    }
    Footer { PrimaryButton("Explore near you", {}, Modifier.fillMaxWidth()) }
}

/** The top of every map screen: search row and layer chips, over the map. */
@Composable
private fun MapTop(selectedLayer: Int, listening: Boolean = true, showListening: Boolean = true) {
    Column(Modifier.fillMaxWidth().padding(top = StatusBar)) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchBar({}, Modifier.weight(1f))
            if (showListening) ListeningButton(listening, {})
        }
        LayerChipRow(
            layers = listOf("Sound", "Light", "Crowd", "Access"),
            selectedIndex = selectedLayer,
            onSelect = {},
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** The bottom of a map screen: attribution, the sheet and the tab bar, pinned to the bottom edge. */
@Composable
private fun MapBottom(attribution: String, sheetGap: Dp = 16.dp, sheet: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) { AttributionPlate(attribution) }
        BottomSheetSurface(gap = sheetGap, content = sheet)
        CiuinTabBar(CiuinTab.Map, {}, bottomInset = 20.dp)
    }
}

@Composable
private fun MapScreen(top: @Composable () -> Unit, bottom: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(CiuinColors.MapGround)) {
        Box(Modifier.align(Alignment.TopStart)) { top() }
        Box(Modifier.align(Alignment.BottomStart)) { bottom() }
    }
}

private const val NoiseCredit = "© OpenStreetMap · noise: sonBASE © FOEN"

@Composable
internal fun MapSound() = MapScreen(
    top = { MapTop(selectedLayer = 0) },
    bottom = {
        MapBottom(NoiseCredit) {
            LayerHeaderReplica(LayerCharacter.Sound, "This area · Sound") { BigReading("60 dB", "Moderate") }
            SegmentedControl(listOf("Day", "Night"), 0, {})
            LevelLegend(listOf(Level.Calm to "Quiet, under 50", Level.Moderate to "Moderate", Level.Loud to "Loud, over 65"))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Where it comes from", style = CiuinType.Label)
                BarRow("Road", 0.78f, "62 dB", labelWidth = 48.dp, valueWidth = 48.dp, gap = 12.dp)
                BarRow("Rail", 0.52f, "55 dB", labelWidth = 48.dp, valueWidth = 48.dp, gap = 12.dp)
            }
        }
    },
)

@Composable
internal fun MapLightNoData() = MapScreen(
    top = { MapTop(selectedLayer = 1) },
    bottom = {
        MapBottom("© OpenStreetMap contributors") {
            LayerHeaderReplica(LayerCharacter.Light, "This area · Light") {
                Text("No light readings yet", style = CiuinType.SheetTitle)
                Text(
                    "Light and flicker come from phones. One 30 second measure fills this in.",
                    style = CiuinType.BodySmall,
                    color = CiuinColors.SecondaryText,
                )
            }
            LevelLegend(listOf(Level.Calm to "Dim", Level.Moderate to "Moderate", Level.Loud to "Bright", Level.NoData to "No data"))
            PrimaryButton("Measure where I am", {}, Modifier.fillMaxWidth())
        }
    },
)

@Composable
internal fun MapAccess() = MapScreen(
    top = { MapTop(selectedLayer = 3) },
    bottom = {
        MapBottom("© OpenStreetMap contributors") {
            LayerHeaderReplica(LayerCharacter.Access, "This area · Access") {
                Text("63% wheelchair accessible", style = CiuinType.SheetTitle)
                Text("From 521 tagged places", style = CiuinType.BodySmall, color = CiuinColors.SecondaryText)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BarRow("Accessible", 0.63f, "63%") { LevelGlyph(Level.Calm, size = LevelGlyphSize.Small, lane = 16.dp) }
                BarRow("Limited", 0.18f, "18%") { LevelGlyph(Level.Moderate, size = LevelGlyphSize.Small, lane = 16.dp) }
                BarRow("Not accessible", 0.19f, "19%") { LevelGlyph(Level.Loud, size = LevelGlyphSize.Small, lane = 16.dp) }
            }
            Text(
                "Empty hexagons have no tagged places. Tap a place to add what you know.",
                style = CiuinType.Caption,
                color = CiuinColors.SecondaryText,
            )
        }
    },
)

/** Not a Paper artboard: the Crowd layer with a reading, to check the three-sheep tile. */
@Composable
internal fun MapCrowd() = MapScreen(
    top = { MapTop(selectedLayer = 2) },
    bottom = {
        MapBottom("© OpenStreetMap contributors") {
            LayerHeaderReplica(LayerCharacter.Crowd, "This area · Crowd") { BigReading("12 devices", "Busy") }
            LevelLegend(listOf(Level.Calm to "Quiet", Level.Moderate to "Busy", Level.Loud to "Crowded", Level.NoData to "No data"))
        }
    },
)

/** Not a Paper artboard: the Light layer with a reading, to check the streetlight tile. */
@Composable
internal fun MapLight() = MapScreen(
    top = { MapTop(selectedLayer = 1) },
    bottom = {
        MapBottom("© OpenStreetMap contributors") {
            LayerHeaderReplica(LayerCharacter.Light, "This area · Light") { BigReading("82 lux", "Moderate") }
            LevelLegend(listOf(Level.Calm to "Dim", Level.Moderate to "Moderate", Level.Loud to "Bright", Level.NoData to "No data"))
        }
    },
)

/** The layer sheet header from `MapInsights.kt`: the layer's sheep, then the eyebrow over [content]. */
@Composable
private fun LayerHeaderReplica(character: LayerCharacter, eyebrow: String, content: @Composable ColumnScope.() -> Unit) =
    LayerHeaderReplica(eyebrow, { CharacterTile { LayerSheep(character) } }, content)

@Composable
private fun LayerHeaderReplica(eyebrow: String, picture: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // The picture is pinned to the top and the text starts a little lower, so the picture's top sits
    // just above the eyebrow however many lines the text runs to (as on the Sound sheet).
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        picture()
        Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(eyebrow, style = CiuinType.Eyebrow, color = CiuinColors.SecondaryText)
            content()
        }
    }
}

@Composable
internal fun MapOffline() = MapScreen(
    top = {
        Column {
            MapTop(selectedLayer = 0)
            OfflineBanner({}, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
        }
    },
    bottom = {
        MapBottom(NoiseCredit, sheetGap = 14.dp) {
            TitleBlock(
                title = "Calm for you",
                eyebrow = "Around you · saved data",
                subtitle = "Listening and alerts still work without a connection.",
            )
            InfoTile("2 measurements waiting", "They send when you're back online") { CiuinIcon(CiuinIcons.Clock01, null) }
        }
    },
)

@Composable
internal fun Search() = Screen {
    Content(top = 2.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchField("caf", {}, Modifier.weight(1f))
            TextLinkButton("Cancel", {})
        }
        Column {
            SectionLabel("Matches")
            PlaceResultRow(Level.Calm, "Café du Grütli", "Café · 80 m · open now", {})
            PlaceResultRow(Level.Moderate, "Café de Grancy", "Café · 450 m · open now", {})
            PlaceResultRow(Level.Loud, "Cafétéria Flon", "Cafeteria · 200 m · open now", {}, levelWord = "Too much")
            PlaceResultRow(Level.NoData, "Café Romand", "Restaurant · 600 m · closed", {}, divider = false)
        }
        Column {
            SectionLabel("Recent")
            RecentRow("Library", {})
            RecentRow("Parc de Mon Repos", {}, divider = false)
        }
    }
}

@Composable
internal fun PlaceSheet() = Box(Modifier.fillMaxSize().background(CiuinColors.MapGround)) {
    // The artboard's home indicator is the sheet's last child: an 18 dp gap and a 10 dp strip.
    BottomSheetSurface(Modifier.align(Alignment.BottomStart), gap = 18.dp, bottomPadding = 8.dp, bottomInset = 28.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            TitleBlock(
                title = "Café du Grütli",
                modifier = Modifier.weight(1f),
                subtitle = "Café · open until 19:00 · 80 m away",
                titleStyle = CiuinType.PlaceTitle,
            )
            RoundIconButton(CiuinIcons.Cancel01, "Close", {})
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
        Column {
            DimensionRow(Level.Moderate, "Sound", "Moderate", detail = "58 dB measured")
            DimensionRow(Level.Calm, "Light", "Dim", detail = "80 lux · no flicker")
            DimensionRow(Level.Calm, "Crowd", "Quiet", detail = "about 3 people")
            DimensionRow(Level.NoData, "Access", "No data yet", actionLabel = "Add info", divider = false)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TagChip("Quiet room")
            TagChip("Outdoor seating")
            TagChip("No music")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Here now?", Modifier.weight(1f), style = CiuinType.BodySmall, color = CiuinColors.SecondaryText)
            SecondaryButton("Too much", {}, size = SecondaryButtonSize.Small)
            SecondaryButton("This is fine", {}, size = SecondaryButtonSize.Small)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Take me there", {}, Modifier.weight(1f), leadingIcon = CiuinIcons.Navigation03, compact = true)
            SecondaryButton("Measure here", {}, Modifier.weight(1f), SecondaryButtonSize.Sheet)
        }
    }
}

@Composable
internal fun MeasureStart() = Screen {
    Content {
        Copy("Measure", "Pick your place. It takes 30 seconds.", gap = 8.dp)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SelectableCard("Café du Grütli", "Café · 10 m · 4 measurements", true, {})
            SelectableCard("Pharmacie du Flon", "Pharmacy · 35 m · not measured yet", false, {})
            SelectableCard("Somewhere else", "Measure this spot without a place", false, {})
        }
        WoolCard(WoolCardStyle.HowTo) {
            Sheep(SheepPose.Reading, Modifier.size(72.dp), contentDescription = null, microphone = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChecklistItem("Hold the phone steady")
                ChecklistItem("Aim the camera at the room")
                ChecklistItem("Audio and video stay here")
            }
        }
    }
    Box(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)) {
        PrimaryButton("Start measuring", {}, Modifier.fillMaxWidth())
    }
    CiuinTabBar(CiuinTab.Measure, {}, bottomInset = 20.dp)
}

@Composable
internal fun MeasureCapturing() = Screen {
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        CenteredHeading("Measuring", "Café du Grütli")
        MeasureRing(0.4f) { Sheep(SheepPose.Reading, Modifier.size(156.dp), contentDescription = null, microphone = true) }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("18 seconds left", style = CiuinType.Display)
            Text(
                "Hold steady and point the camera at the room.",
                style = CiuinType.Body,
                color = CiuinColors.SecondaryText,
                textAlign = TextAlign.Center,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("57 dB", "Sound", Modifier.weight(1f), StatTileStyle.LabelFirst)
            StatTile("82 lux", "Light", Modifier.weight(1f), StatTileStyle.LabelFirst)
            StatTile("3", "People", Modifier.weight(1f), StatTileStyle.LabelFirst)
        }
    }
    Footer(gap = 16.dp) {
        IconNote(CiuinIcons.SquareLock02, "Audio and video stay on this phone.")
        SecondaryButton("Cancel", {}, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun MeasureReview() = Screen {
    Content {
        Column {
            ReadingRow(Level.Moderate, "Sound · Moderate", "Mostly people talking", "57 dB", valueNote = "peak 71")
            ReadingRow(Level.Calm, "Light · Dim", "No flicker found", "82 lux")
            ReadingRow(Level.Calm, "Crowd · Quiet", "Counted, pictures not kept", "3 people", divider = false)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("How did it feel to you?", style = CiuinType.TileTitle)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceButton("Too much", false, {}, Modifier.weight(1f))
                ChoiceButton("This is fine", true, {}, Modifier.weight(1f))
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
    }
    Footer(gap = 10.dp) {
        PrimaryButton("Share measurement", {}, Modifier.fillMaxWidth())
        TextLinkButton("Discard", {}, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun MeasureShared() = Screen {
    Content(top = 16.dp, gap = 24.dp) {
        SharedScene()
        Copy("Shared with Ciúin. Thanks for adding to the map.", "Café du Grütli now has 5 measurements, and your map is up to date.")
        SummaryTile("57 dB · 82 lux · 3 people")
    }
    Footer(gap = 10.dp) {
        PrimaryButton("Back to the map", {}, Modifier.fillMaxWidth())
        TextLinkButton("Measure another place", {}, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun Alert() = Screen {
    Content(top = 16.dp, gap = 22.dp) {
        AlertScene("Too much · 74 dB")
        Copy("It's getting loud here.", "Above your limit for 20 seconds. There's a calm place two minutes away.")
        QuietPlaceCard(Level.Calm, "Library · Calm", "120 m · 2 min walk · open now", "41 dB") {}
    }
    Footer(gap = 10.dp) {
        PrimaryButton("Take me there", {}, Modifier.fillMaxWidth(), leadingIcon = CiuinIcons.Navigation03)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("I'm okay here", {}, Modifier.weight(1f), SecondaryButtonSize.Medium)
            SecondaryButton("Snooze 15 min", {}, Modifier.weight(1f), SecondaryButtonSize.Medium)
        }
    }
}

@Composable
internal fun Route() = Box(Modifier.fillMaxSize().background(CiuinColors.MapGround)) {
    Box(Modifier.padding(top = StatusBar).padding(start = 16.dp, end = 16.dp, top = 2.dp)) {
        WalkingBanner("Walking to Library", "120 m · 2 min", {})
    }
    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
        Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) { AttributionPlate("© OpenStreetMap · routing: OSRM") }
        BottomSheetSurface(bottomPadding = 8.dp, bottomInset = 26.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                CharacterTile { Sheep(SheepPose.Awake, Modifier.size(56.dp), contentDescription = null) }
                TitleBlock("Getting calmer", Modifier.weight(1f), subtitle = "74 dB here, 41 dB at the Library")
            }
            Column {
                RouteStepRow(CiuinIcons.ArrowLeft01, "Head west on Rue de Genève", "80 m", current = true)
                RouteStepRow(CiuinIcons.Location01, "Library is on your right", "40 m", iconSize = 22.dp, divider = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("End route", {}, Modifier.weight(1f), SecondaryButtonSize.Medium)
                SecondaryButton("I'm okay now", {}, Modifier.weight(1f), SecondaryButtonSize.Medium)
            }
        }
    }
}

@Composable
private fun YouContent() {
    Column {
        SettingsRow("Starting point", "A bit of everything", {})
        SettingsSwitchRow("Listen in the background", "Wakes only when it gets loud", true, {})
        SettingsSwitchRow("Gentle alerts", "One vibration, then a calm place", true, {})
        SettingsSwitchRow("Reduce motion", "Sheep and ducks hold still", false, {})
        SettingsRow("What is shared", "Numbers are sent, taps stay here", {})
        DestructiveRow("Reset what the sheep learned", {})
    }
}

@Composable
internal fun You() = Screen {
    Content {
        Text("You", style = CiuinType.ScreenTitle)
        YouContent()
    }
    CiuinTabBar(CiuinTab.You, {}, bottomInset = 20.dp)
}

@Composable
internal fun ResetSheet() = Box(Modifier.fillMaxSize().background(CiuinColors.Scrim)) {
    BottomSheetSurface(
        modifier = Modifier.align(Alignment.BottomStart),
        horizontalPadding = 24.dp,
        gap = 20.dp,
        bottomPadding = 8.dp,
        bottomInset = 30.dp,
        shadow = false,
    ) {
        IconDisc(CiuinIcons.Delete02, iconSize = 26.dp)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Reset what the sheep learned?", style = CiuinType.PlaceTitle)
            Text(
                "Your sheep forgets your 23 answers and goes back to A bit of everything. Your measurements stay.",
                style = CiuinType.Body,
                color = CiuinColors.SecondaryText,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Keep what it learned", {}, Modifier.fillMaxWidth())
            DestructiveButton("Reset", {}, Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun Model() = Screen {
    Content(top = 0.dp, gap = 16.dp) {
        CiuinTopBar(title = "On this phone", onBack = {})
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
        Column {
            SectionLabel("Model size", bottomPadding = 0.dp)
            RadioRow("Small", "1.2 MB · lightest on the battery", RadioState.Selected, {})
            RadioRow("Medium", "1.9 MB · for stronger phones", RadioState.Unselected, {})
            RadioRow("Large", "Not available yet", RadioState.Unavailable, {}, divider = false)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Listening today", bottomPadding = 0.dp)
            BarRow("Asleep, no model", 0.82f, "82%", labelWidth = 150.dp)
            BarRow("Small model", 0.15f, "15%", labelWidth = 150.dp)
            BarRow("Large model, camera", 0.03f, "3%", labelWidth = 150.dp)
            IconNote(
                icon = CiuinIcons.BatteryFull,
                text = "Most of the time no model is running.",
                modifier = Modifier.padding(top = 2.dp),
                iconSize = 22.dp,
                gap = 10.dp,
                style = CiuinType.Caption,
            )
        }
        LinkRow(learnedFromText(23), "Reset", {})
        Text(
            "Each time you tap Too much or This is fine, your sheep learns what bothers you.",
            style = CiuinType.Caption,
            color = CiuinColors.SecondaryText,
        )
    }
}

@Composable
internal fun Privacy() = Screen {
    Content(top = 0.dp, gap = 18.dp) {
        CiuinTopBar(title = "What is shared", onBack = {})
        Text("Only numbers leave this phone.", style = CiuinType.HeadlineTight)
        Column {
            SectionLabel("Sent to Ciúin")
            SharedFactRow("Average and peak loudness", shared = true)
            SharedFactRow("The mix of sound types", shared = true)
            SharedFactRow("Brightness and flicker", shared = true)
            SharedFactRow("A people count", shared = true)
            SharedFactRow("The place and the time", shared = true, divider = false)
        }
        Column {
            SectionLabel("Stays on this phone")
            SharedFactRow("Audio and camera frames", shared = false)
            SharedFactRow("Your Too much and This is fine taps", shared = false)
            SharedFactRow("Your personal model", shared = false)
            SharedFactRow("Who you are", shared = false, divider = false)
        }
        WoolCard(WoolCardStyle.Note) {
            Sheep(SheepPose.Rest, Modifier.size(72.dp, 66.dp), contentDescription = null, zzz = false)
            Text("The server rejects anything that is not on the first list.", Modifier.weight(1f), style = CiuinType.BodySmall)
        }
    }
}
