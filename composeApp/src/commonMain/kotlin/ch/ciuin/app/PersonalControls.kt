package ch.ciuin.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ch.ciuin.app.personal.PersonalModelSession
import ch.ciuin.shared.model.FeatureVector
import ch.ciuin.shared.model.Place
import ch.ciuin.shared.personal.PersonalPreset
import kotlin.math.roundToInt

internal val Place.personalFeatures: FeatureVector?
    get() = measured?.features ?: prior

@Composable
internal fun PersonalPlaceFeedback(
    place: Place,
    personal: PersonalModelSession?,
    busy: Boolean,
    liveScore: Double?,
    onFeedback: (FeatureVector?, Int) -> Unit,
) {
    val features = place.personalFeatures
    val status = when {
        features != null && personal != null -> "Predicted overwhelm here ${(personal.score(features) * 100).roundToInt()}%"
        liveScore != null -> null
        features == null -> "Feedback needs live audio or place conditions"
        busy -> "Loading personal model…"
        else -> "Personal model unavailable"
    }
    Column {
        status?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
        val source = when {
            liveScore != null -> "Feedback uses the latest live audio window"
            features != null -> Category.PERSONAL.detail(place)
            else -> null
        }
        source?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        PersonalFeedbackButtons(enabled = personal != null && (features != null || liveScore != null) && !busy) {
            onFeedback(features, it)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PersonalFeedbackButtons(enabled: Boolean, onFeedback: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onFeedback(1) }, enabled = enabled) { Text("Too much") }
        OutlinedButton(onClick = { onFeedback(0) }, enabled = enabled) { Text("This is fine") }
    }
}

@Composable
internal fun PersonalSettingsDialog(
    personal: PersonalModelSession?,
    busy: Boolean,
    snackbar: SnackbarHostState,
    onSelectPreset: (PersonalPreset) -> Unit,
    onReset: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset training?") },
            text = { Text("This clears your saved feedback and restores the selected preset.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onReset()
                }) { Text("Reset training") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Personal settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Sensitivity preset", style = MaterialTheme.typography.labelLarge)
                Column(Modifier.selectableGroup()) {
                    for (preset in PersonalPreset.entries) {
                        val selected = preset == personal?.preset
                        val enabled = personal != null && !busy
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                                selected = selected,
                                enabled = enabled,
                                role = Role.RadioButton,
                                onClick = { onSelectPreset(preset) },
                            ),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null, enabled = enabled)
                            Text(preset.displayName)
                        }
                    }
                }
                when {
                    personal != null -> {
                        val count = personal.exampleCount
                        Text(if (count == 1) "1 saved example" else "$count saved examples")
                    }
                    busy -> Text("Loading personal model…")
                    else -> Text("Personal model unavailable")
                }
                SnackbarHost(snackbar)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        dismissButton = {
            when {
                personal != null -> {
                    TextButton(onClick = { confirmReset = true }, enabled = personal.exampleCount > 0 && !busy) {
                        Text("Reset training")
                    }
                }
                !busy -> TextButton(onClick = onRetry) { Text("Retry") }
                else -> Unit
            }
        },
    )
}

private val PersonalPreset.displayName: String
    get() = when (this) {
        PersonalPreset.AUTISM_FRIENDLY -> "Autism-friendly"
        PersonalPreset.SOUND_SENSITIVE -> "Sound-sensitive"
        PersonalPreset.LIGHT_SENSITIVE -> "Light-sensitive"
        PersonalPreset.CROWD_SENSITIVE -> "Crowd-sensitive"
    }
