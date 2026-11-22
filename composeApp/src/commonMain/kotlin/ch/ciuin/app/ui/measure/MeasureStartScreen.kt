package ch.ciuin.app.ui.measure

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.ciuin.app.ui.characters.Sheep
import ch.ciuin.app.ui.characters.SheepPose
import ch.ciuin.app.ui.components.ChecklistItem
import ch.ciuin.app.ui.components.IconNote
import ch.ciuin.app.ui.components.Label
import ch.ciuin.app.ui.components.PrimaryButton
import ch.ciuin.app.ui.components.SearchBar
import ch.ciuin.app.ui.components.SecondaryButton
import ch.ciuin.app.ui.components.SecondaryButtonSize
import ch.ciuin.app.ui.components.SelectableCard
import ch.ciuin.app.ui.components.WoolCard
import ch.ciuin.app.ui.components.WoolCardStyle
import ch.ciuin.app.ui.icons.CiuinIcons
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinType
import ch.ciuin.shared.model.Place

internal data class PlaceChoice(val place: Place?, val subtitle: String)

/** What the start screen says about the phone's location while it cannot list the places around you. */
internal enum class LocationNotice {
    /** Not asked yet: "Allow". */
    Ask,

    /** Refused: it can only be turned on in Settings. */
    Off,

    /** This phone cannot locate itself. */
    Unavailable,

    /** Allowed, waiting for the first fix. */
    Finding,
}

/**
 * Measure start: pick a place, read the how-to, "Start measuring". Shows [tabBar] at the bottom.
 *
 * [canMeasure] is false on a device without the sensors to measure: the screen says so and the
 * button is disabled. [nothingNearby] notes that the map has no place close by, so only "Somewhere
 * else" is offered. [error] is the last start failure, said above the button.
 */
@Composable
internal fun MeasureStartScreen(
    choices: List<PlaceChoice>,
    selected: PlaceChoice?,
    onSelect: (PlaceChoice) -> Unit,
    onStart: () -> Unit,
    tabBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    canMeasure: Boolean = true,
    locationNotice: LocationNotice? = null,
    onAllowLocation: () -> Unit = {},
    onFindPlace: (() -> Unit)? = null,
    nothingNearby: Boolean = false,
    error: String? = null,
    canStart: Boolean = true,
) {
    MeasureFrame(modifier, bottomInset = false) {
        MeasureContent {
            MeasureCopyBlock(
                title = "Measure",
                body = if (canMeasure) "Pick your place. It takes 30 seconds." else "This phone can't take measurements.",
                gap = 8.dp,
            )
            if (canMeasure) {
                if (choices.isNotEmpty() || nothingNearby) {
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (nothingNearby) {
                            Label(
                                "No places on the map right here. You can still measure this spot.",
                                CiuinType.BodySmall,
                                color = CiuinColors.SecondaryText,
                            )
                        }
                        choices.forEach { choice ->
                            SelectableCard(
                                choice.place?.name ?: "Somewhere else",
                                choice.subtitle,
                                choice == selected,
                                { onSelect(choice) },
                            )
                        }
                    }
                }
                if (locationNotice != null) LocationNoticeRow(locationNotice, onAllowLocation)
                // The map's own search bar, so it reads as something to tap rather than a line of text.
                if (onFindPlace != null) SearchBar(onFindPlace, Modifier.fillMaxWidth(), placeholder = "Search for a place")
                WoolCard(WoolCardStyle.HowTo) {
                    Sheep(SheepPose.Reading, Modifier.size(72.dp), contentDescription = null, microphone = true)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChecklistItem("Hold the phone steady")
                        ChecklistItem("Aim the camera at the room")
                        ChecklistItem("Audio and video stay here")
                    }
                }
            } else {
                WoolCard(WoolCardStyle.HowTo) {
                    Sheep(SheepPose.Reading, Modifier.size(72.dp), contentDescription = null, microphone = true)
                    Label(
                        "Measuring needs a microphone, a camera or nearby-device scanning, and this device has none of them. " +
                            "The map still shows what others measured.",
                        CiuinType.BodySmall,
                        Modifier.weight(1f),
                    )
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (error != null) {
                Label(
                    error,
                    CiuinType.BodySmall,
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = CiuinColors.Destructive,
                )
            }
            if (canMeasure) {
                PrimaryButton(
                    "Start measuring",
                    onStart,
                    Modifier.fillMaxWidth(),
                    enabled = canStart && selected != null,
                )
            }
        }
        tabBar()
    }
}

@Composable
private fun LocationNoticeRow(notice: LocationNotice, onAllow: () -> Unit) {
    val text = when (notice) {
        LocationNotice.Ask -> "Allow your location to see the places around you."
        LocationNotice.Off -> "Location is off. Turn it on in Settings to see the places around you."
        LocationNotice.Unavailable -> "This phone can't tell where it is, so places nearby can't be listed."
        LocationNotice.Finding -> "Finding the places around you…"
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.weight(1f)) {
            IconNote(CiuinIcons.Location01, text, gap = 10.dp, iconSize = 20.dp)
        }
        if (notice == LocationNotice.Ask) SecondaryButton("Allow", onAllow, size = SecondaryButtonSize.Compact)
    }
}
