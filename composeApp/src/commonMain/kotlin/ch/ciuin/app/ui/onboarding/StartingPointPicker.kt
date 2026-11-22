package ch.ciuin.app.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.ciuin.shared.personal.PersonalPreset

/** What the design calls a preset: "A bit of everything", "Sound", "Light", "Crowds". */
internal val PersonalPreset.startingPointTitle: String
    get() = when (this) {
        PersonalPreset.AUTISM_FRIENDLY -> "A bit of everything"
        PersonalPreset.SOUND_SENSITIVE -> "Sound"
        PersonalPreset.LIGHT_SENSITIVE -> "Light"
        PersonalPreset.CROWD_SENSITIVE -> "Crowds"
    }

/** The four "What bothers you most?" cards; shared by onboarding step 3 and You > Starting point. */
@Composable
internal fun StartingPointPicker(
    selected: PersonalPreset?,
    onSelect: (PersonalPreset) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(modifier)
}
