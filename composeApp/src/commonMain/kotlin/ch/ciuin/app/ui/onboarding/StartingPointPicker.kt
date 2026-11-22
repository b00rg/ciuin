package ch.ciuin.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ch.ciuin.app.ui.components.SelectableCard
import ch.ciuin.app.ui.icons.CiuinIcons
import ch.ciuin.shared.personal.PersonalPreset

/** What the design calls a preset: "A bit of everything", "Sound", "Light", "Crowds". */
internal val PersonalPreset.startingPointTitle: String
    get() = when (this) {
        PersonalPreset.AUTISM_FRIENDLY -> "A bit of everything"
        PersonalPreset.SOUND_SENSITIVE -> "Sound"
        PersonalPreset.LIGHT_SENSITIVE -> "Light"
        PersonalPreset.CROWD_SENSITIVE -> "Crowds"
    }

/** The grey line under each preset's title on the cards. */
internal val PersonalPreset.startingPointSubtitle: String
    get() = when (this) {
        PersonalPreset.AUTISM_FRIENDLY -> "The autism-friendly mix."
        PersonalPreset.SOUND_SENSITIVE -> "Noise and sharp peaks."
        PersonalPreset.LIGHT_SENSITIVE -> "Brightness and flicker."
        PersonalPreset.CROWD_SENSITIVE -> "Busy, crowded places."
    }

private val PersonalPreset.icon: ImageVector
    get() = when (this) {
        PersonalPreset.AUTISM_FRIENDLY -> CiuinIcons.SlidersHorizontal
        PersonalPreset.SOUND_SENSITIVE -> CiuinIcons.VolumeHigh
        PersonalPreset.LIGHT_SENSITIVE -> CiuinIcons.Sun03
        PersonalPreset.CROWD_SENSITIVE -> CiuinIcons.UserGroup
    }

/** The order the cards are drawn in, the artboard's. */
private val PickerOrder = listOf(
    PersonalPreset.AUTISM_FRIENDLY,
    PersonalPreset.SOUND_SENSITIVE,
    PersonalPreset.LIGHT_SENSITIVE,
    PersonalPreset.CROWD_SENSITIVE,
)

/**
 * The four "What bothers you most?" cards; shared by onboarding step 3 and You > Starting point.
 *
 * Each card is a radio button (icon on a wool disc, title, grey line, radio mark), 12 dp apart. It
 * only reports the tap: the caller decides when to apply it (onboarding applies it on Continue).
 * With `enabled = false` the cards dim and ignore taps, for example while the choice is saving.
 * A null [selected] shows no card chosen.
 */
@Composable
internal fun StartingPointPicker(
    selected: PersonalPreset?,
    onSelect: (PersonalPreset) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (preset in PickerOrder) {
            SelectableCard(
                title = preset.startingPointTitle,
                subtitle = preset.startingPointSubtitle,
                selected = preset == selected,
                onClick = { onSelect(preset) },
                leadingIcon = preset.icon,
                enabled = enabled,
            )
        }
    }
}
