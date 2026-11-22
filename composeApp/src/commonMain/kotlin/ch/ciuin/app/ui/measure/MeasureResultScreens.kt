package ch.ciuin.app.ui.measure

import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.ciuin.app.measure.MEASUREMENT_SECONDS
import ch.ciuin.app.measure.MeasurementPhase
import ch.ciuin.app.measure.MeasurementReadings
import ch.ciuin.app.measure.MeasurementReview
import ch.ciuin.app.ui.characters.SharedScene
import ch.ciuin.app.ui.characters.Sheep
import ch.ciuin.app.ui.characters.SheepPose
import ch.ciuin.app.ui.components.CenteredHeading
import ch.ciuin.app.ui.components.ChoiceButton
import ch.ciuin.app.ui.components.IconNote
import ch.ciuin.app.ui.components.MeasureRing
import ch.ciuin.app.ui.components.PrimaryButton
import ch.ciuin.app.ui.components.ReadingRow
import ch.ciuin.app.ui.components.SecondaryButton
import ch.ciuin.app.ui.components.SoundWaveform
import ch.ciuin.app.ui.components.StatTile
import ch.ciuin.app.ui.components.StatTileStyle
import ch.ciuin.app.ui.components.SummaryTile
import ch.ciuin.app.ui.components.TextLinkButton
import ch.ciuin.app.ui.icons.CiuinIcons
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinType
import ch.ciuin.app.ui.theme.LocalReduceMotion
import ch.ciuin.shared.model.Place
import kotlin.time.Duration.Companion.seconds

@Composable
internal fun MeasureCaptureScreen(
    placeName: String,
    capture: MeasurementPhase.Capturing?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    levels: Flow<Float> = emptyFlow(),
) {
    val readings = capture?.readings ?: MeasurementReadings()
    val progress = capture?.let { (MEASUREMENT_SECONDS - it.remainingSeconds).toFloat() / MEASUREMENT_SECONDS } ?: 0f
    val startedAt = capture?.startedAt
    val reduceMotion = LocalReduceMotion.current
    val smoothProgress = remember(startedAt) { mutableFloatStateOf(progress) }
    LaunchedEffect(startedAt, reduceMotion) {
        if (startedAt == null || reduceMotion) return@LaunchedEffect
        val duration = MEASUREMENT_SECONDS.seconds
        while (smoothProgress.floatValue < 1f) {
            withFrameNanos {
                smoothProgress.floatValue = (startedAt.elapsedNow() / duration).toFloat().coerceIn(0f, 1f)
            }
        }
    }
    val detail = when {
        capture == null -> "Allow the sensors you want to use."
        capture.permissions.camera -> "Hold steady and point the camera at the room."
        else -> "Hold the phone steady and keep Ciúin open."
    }
    MeasureFrame(modifier) {
        MeasureContent(gap = 24.dp, horizontalAlignment = Alignment.CenterHorizontally) {
            CenteredHeading("Measuring", placeName)
            BoxWithConstraints {
                val diameter = 264.dp.coerceAtMost(maxWidth)
                MeasureRing(
                    progress,
                    drawProgress = { if (reduceMotion) progress else smoothProgress.floatValue },
                    diameter = diameter,
                ) {
                    Sheep(SheepPose.Reading, Modifier.size(diameter * (156f / 264f)), contentDescription = null, microphone = true)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    capture?.let { secondsLeft(it.remainingSeconds) } ?: "Getting ready",
                    style = CiuinType.Display,
                    textAlign = TextAlign.Center,
                )
                Text(detail, style = CiuinType.Body, color = CiuinColors.SecondaryText, textAlign = TextAlign.Center)
            }
            if (capture?.permissions?.microphone == true) SoundWaveform(levels, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val sound = readings.db?.let { if (readings.dbfs) whole(it) else "${whole(it)} dB" }
                StatTile(sound ?: NO_VALUE, if (readings.dbfs) "dBFS" else "Sound", Modifier.weight(1f), StatTileStyle.LabelFirst)
                StatTile(readings.lux?.let(::luxText) ?: NO_VALUE, "Light", Modifier.weight(1f), StatTileStyle.LabelFirst)
                StatTile(readings.nearbyDevices?.toString() ?: NO_VALUE, "Crowd", Modifier.weight(1f), StatTileStyle.LabelFirst)
            }
        }
        MeasureFooter(gap = 16.dp) {
            IconNote(CiuinIcons.SquareLock02, "Audio and video stay on this phone.")
            SecondaryButton("Cancel", onCancel, Modifier.fillMaxWidth())
        }
    }
}

internal enum class MeasureFeedback(val title: String, val label: Int) {
    TooMuch("Too much", 1),
    Fine("This is fine", 0),
}

@Composable
internal fun MeasureReviewScreen(
    review: MeasurementReview,
    place: Place?,
    feedback: MeasureFeedback?,
    onFeedback: ((MeasureFeedback) -> Unit)?,
    onShare: () -> Unit,
    onDiscard: () -> Unit,
    uploading: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
) {
    MeasureFrame(modifier) {
        MeasureContent {
            Column {
                val lines = listOf(soundLine(review), lightLine(review), crowdLine(review))
                lines.forEachIndexed { index, line ->
                    ReadingRow(
                        level = line.level,
                        title = line.title,
                        subtitle = line.subtitle,
                        value = line.value,
                        valueNote = line.valueNote,
                        divider = index < lines.lastIndex,
                    )
                }
            }
            if (onFeedback != null && review.canUpload) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("How did it feel to you?", style = CiuinType.TileTitle)
                    Row(
                        Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        for (answer in MeasureFeedback.entries) {
                            ChoiceButton(answer.title, answer == feedback, { onFeedback(answer) }, Modifier.weight(1f))
                        }
                    }
                }
            }
            if (review.canUpload) {
                // What sharing sends, said before the button rather than in a reward card.
                Text(rewardLine(place, review), style = CiuinType.Caption, color = CiuinColors.SecondaryText)
            } else {
                Text("There are no readings to share.", color = CiuinColors.SecondaryText, style = CiuinType.Body)
            }
        }
        MeasureFooter(gap = 10.dp) {
            if (error != null) {
                Text(
                    error,
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = CiuinColors.Destructive,
                    style = CiuinType.BodySmall,
                )
            }
            if (review.canUpload) {
                PrimaryButton(
                    if (uploading) "Sharing measurement…" else "Share measurement",
                    onShare,
                    Modifier.fillMaxWidth(),
                    enabled = !uploading,
                )
            }
            TextLinkButton(
                if (review.canUpload) "Discard" else "Back to Measure",
                onDiscard,
                Modifier.fillMaxWidth(),
                enabled = !uploading,
            )
        }
    }
}

@Composable
internal fun MeasureSharedScreen(
    stored: Boolean,
    updatedPlace: Place?,
    summary: String?,
    onBackToMap: () -> Unit,
    onMeasureAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = if (stored) "Shared with Ciúin. Thanks for adding to the map." else "Queued until you're online."
    val count = updatedPlace?.measured?.n?.takeIf { it > 0 }
    val detail = when {
        !stored -> "Keep Ciúin running so it can send this reading. If the app stops before sending, the reading is lost."
        updatedPlace != null && count != null -> "${updatedPlace.name} now has ${measurementCount(count)}, and your map is up to date."
        else -> "Your reading was sent to Ciúin."
    }
    MeasureFrame(modifier) {
        MeasureContent(top = 16.dp, gap = 24.dp) {
            if (stored) SharedScene()
            MeasureCopyBlock(title, detail)
            if (summary != null) SummaryTile(summary)
        }
        MeasureFooter(gap = 10.dp) {
            PrimaryButton("Back to the map", onBackToMap, Modifier.fillMaxWidth())
            TextLinkButton("Measure another place", onMeasureAgain, Modifier.fillMaxWidth())
        }
    }
}
