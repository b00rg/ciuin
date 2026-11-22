@file:OptIn(kotlin.time.ExperimentalTime::class)

package ch.ciuin.app.ui.place

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.ciuin.app.Category
import ch.ciuin.app.core.AppEnvironment
import ch.ciuin.app.insight.placeFacts
import ch.ciuin.app.personalFeatures
import ch.ciuin.app.route.RoutePoint
import ch.ciuin.app.route.distanceMeters
import ch.ciuin.app.ui.characters.Sheep
import ch.ciuin.app.ui.characters.SheepPose
import ch.ciuin.app.ui.components.BottomSheetSurface
import ch.ciuin.app.ui.components.DimensionRow
import ch.ciuin.app.ui.components.Level
import ch.ciuin.app.ui.components.PrimaryButton
import ch.ciuin.app.ui.components.RoundIconButton
import ch.ciuin.app.ui.components.SecondaryButton
import ch.ciuin.app.ui.components.SecondaryButtonSize
import ch.ciuin.app.ui.components.TagChip
import ch.ciuin.app.ui.components.TitleBlock
import ch.ciuin.app.ui.components.WoolCard
import ch.ciuin.app.ui.components.WoolCardStyle
import ch.ciuin.app.ui.icons.CiuinIcons
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinType
import ch.ciuin.app.ui.toUi
import ch.ciuin.shared.model.Place
import androidx.compose.material3.Text
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The place detail sheet the map shows over itself for the selected place: the sheet surface and all of
 * its content. Null [onTakeMeThere] / [onMeasure] hide that button (no location, no sensors).
 *
 * The title row stays put; everything under it scrolls once the sheet would be taller than about 72%
 * of the height its parent offers. Host it in a box the size of the screen, aligned to the bottom, and
 * pass the navigation-bar height as [bottomInset].
 */
@Composable
internal fun PlaceSheet(
    place: Place,
    environment: AppEnvironment,
    onClose: () -> Unit,
    onTakeMeThere: (() -> Unit)?,
    onMeasure: (() -> Unit)?,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    PlaceSheet(place, environment, onClose, onTakeMeThere, onMeasure, modifier, bottomInset, Clock.System.now())
}

/** [now] is a parameter so the hours can be pinned in tests. */
@Composable
internal fun PlaceSheet(
    place: Place,
    environment: AppEnvironment,
    onClose: () -> Unit,
    onTakeMeThere: (() -> Unit)?,
    onMeasure: (() -> Unit)?,
    modifier: Modifier,
    bottomInset: Dp,
    now: Instant,
) {
    val facts = remember(place, now) { placeFacts(place, now) }
    val here = environment.location.current
    val subtitle = listOfNotNull(
        facts.categoryLabel.takeIf { it.isNotBlank() },
        hoursText(facts.hours),
        here?.let { distanceText(distanceMeters(it, RoutePoint(place.lat, place.lon))) }?.let { "$it away" },
    ).joinToString(" · ")

    BottomSheetSurface(
        modifier = modifier.maxHeightFraction(MAX_SHEET_FRACTION),
        gap = SheetGap,
        bottomPadding = 8.dp,
        bottomInset = bottomInset,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            TitleBlock(
                title = place.name,
                modifier = Modifier.weight(1f),
                subtitle = subtitle.ifEmpty { null },
                titleStyle = CiuinType.PlaceTitle,
            )
            RoundIconButton(CiuinIcons.Cancel01, "Close", onClose)
        }
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SheetGap),
        ) {
            Verdict(place, environment)
            Dimensions(place, facts.osmUrl)
            FactChips(facts.chips.mapNotNull { it.label })
            HereNow(place, environment)
            Actions(onTakeMeThere, onMeasure)
        }
    }
}

/** The sheep's verdict for you: the personal model's score for this place, and how much it rests on. */
@Composable
private fun Verdict(place: Place, environment: AppEnvironment) {
    val features = place.personalFeatures
    val session = environment.personalModel.state.session
    val score = features?.let { session?.score(it) }
    val level = personalLevel(score)
    val count = place.measured?.n ?: 0
    val basis = when (count) {
        0 -> "an estimate, not measured yet"
        1 -> "1 measurement"
        else -> "$count measurements"
    }
    val (pose, title, line) = when {
        features == null -> Triple(SheepPose.Reading, "Nobody has measured here yet", "The sheep can't tell how it feels for you yet.")
        score == null -> Triple(SheepPose.Reading, "The sheep is still waking up", "Your settings are loading.")
        level == Level.Calm -> Triple(SheepPose.Awake, "Likely fine for you", "Low overload risk · $basis")
        level == Level.Moderate -> Triple(SheepPose.Awake, "Could be a lot for you", "Some overload risk · $basis")
        else -> Triple(SheepPose.Alert, "Likely too much for you", "High overload risk · $basis")
    }
    WoolCard(WoolCardStyle.Verdict) {
        Sheep(pose, Modifier.size(60.dp), contentDescription = null, haptic = false)
        TitleBlock(
            title = title,
            modifier = Modifier.weight(1f),
            subtitle = line,
            titleStyle = CiuinType.CardTitle,
            subtitleStyle = CiuinType.Caption,
        )
    }
}

/** One row per sense. The words say where each value comes from; nothing is shown that the app lacks. */
@Composable
private fun Dimensions(place: Place, osmUrl: String?) {
    val uriHandler = LocalUriHandler.current
    val readings = place.measured?.features
    Column {
        // Sound: our measurement, else the outdoor road and rail noise model around the place.
        val measuredDb = readings?.dbMean
        val soundDb = measuredDb ?: place.baselineNoiseDb
        val soundLevel = soundDb?.let { Category.SOUND.levelOf(it) }
        DimensionRow(
            level = soundLevel.toUi(),
            name = "Sound",
            levelWord = soundLevel?.let { Category.SOUND.levelName(it) } ?: NO_DATA,
            detail = when {
                measuredDb != null -> "${dbText(measuredDb)} measured"
                soundDb != null -> "est. ${dbText(soundDb)} outside"
                else -> null
            },
        )

        // Light: only ever measured.
        val lux = readings?.luxMean
        val lightLevel = lux?.let { Category.LIGHT.levelOf(it) }
        DimensionRow(
            level = lightLevel.toUi(),
            name = "Light",
            levelWord = lightLevel?.let { Category.LIGHT.levelName(it) } ?: NO_DATA,
            detail = lux?.let { value ->
                val flicker = readings.flickerIndex?.let { if (it < FLICKER_NOTICEABLE) "no flicker" else "some flicker" }
                listOfNotNull("${value.toInt()} lux", flicker).joinToString(" · ")
            },
        )

        // Crowd: nearby Bluetooth devices when measured; an estimate from the place's prior otherwise.
        val devices = readings?.nearbyDevices
        val estimatedDevices = place.prior?.nearbyDevices
        val estimatedPeople = place.prior?.peopleCount
        val crowdLevel = when {
            devices != null -> Category.CROWD.levelOf(devices.toDouble())
            estimatedDevices != null -> Category.CROWD.levelOf(estimatedDevices.toDouble())
            // Category.kt counts about two devices per person.
            estimatedPeople != null -> Category.CROWD.levelOf(estimatedPeople * DEVICES_PER_PERSON)
            else -> null
        }
        DimensionRow(
            level = crowdLevel.toUi(),
            name = "Crowd",
            levelWord = crowdLevel?.let { Category.CROWD.levelName(it) } ?: NO_DATA,
            detail = when {
                devices != null -> if (devices == 1) "1 device nearby" else "$devices devices nearby"
                estimatedDevices != null -> "est. $estimatedDevices devices"
                estimatedPeople != null -> if (estimatedPeople == 1) "est. 1 person" else "est. $estimatedPeople people"
                else -> null
            },
        )

        // Access: the OpenStreetMap wheelchair tag. Without one, a link to add it there.
        val access = when (place.wheelchair ?: place.osmTags["wheelchair"]) {
            "yes", "designated" -> Level.Calm to "Accessible"
            "limited" -> Level.Moderate to "Limited"
            "no" -> Level.Loud to "Not accessible"
            else -> null
        }
        DimensionRow(
            level = access?.first ?: Level.NoData,
            name = "Access",
            levelWord = access?.second ?: NO_DATA,
            actionLabel = if (access == null && osmUrl != null) "Add info" else null,
            onAction = { if (osmUrl != null) runCatching { uriHandler.openUri(osmUrl) } },
            divider = false,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FactChips(labels: List<String>) {
    if (labels.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (label in labels) TagChip(label)
    }
}

/**
 * "Here now?" with Too much / This is fine. A tap is saved to the personal model and the row thanks you
 * in place; if saving fails it says so and offers the buttons again. Hidden when there is nothing to
 * learn from (no readings for the place and nothing heard live) or the model has not loaded.
 */
@Composable
private fun HereNow(place: Place, environment: AppEnvironment) {
    val personal = environment.personalModel
    val canLearn = personal.state.session != null && (place.personalFeatures != null || personal.liveScore != null)
    var answered by remember(place.placeId) { mutableStateOf(false) }
    if (!canLearn && !answered) return

    val notice = personal.state.notice?.message
    val failed = answered && notice != null && notice.startsWith("Couldn't")
    Row(
        modifier = Modifier.heightIn(min = 44.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (answered && !failed) {
            Text(
                text = "Thanks. The sheep will remember this.",
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = CiuinType.BodySmall,
                color = CiuinColors.SecondaryText,
            )
        } else {
            Text(
                text = if (failed) notice else "Here now?",
                modifier = Modifier.weight(1f).semantics { if (failed) liveRegion = LiveRegionMode.Polite },
                style = CiuinType.BodySmall,
                color = CiuinColors.SecondaryText,
            )
            fun answer(label: Int) {
                personal.recordFeedback(place.personalFeatures, label)
                answered = true
            }
            SecondaryButton("Too much", { answer(TOO_MUCH) }, size = SecondaryButtonSize.Small, enabled = !personal.state.busy)
            SecondaryButton("This is fine", { answer(FINE) }, size = SecondaryButtonSize.Small, enabled = !personal.state.busy)
        }
    }
}

@Composable
private fun Actions(onTakeMeThere: (() -> Unit)?, onMeasure: (() -> Unit)?) {
    if (onTakeMeThere == null && onMeasure == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (onTakeMeThere != null) {
            PrimaryButton("Take me there", onTakeMeThere, Modifier.weight(1f), leadingIcon = CiuinIcons.Navigation03)
        }
        if (onMeasure != null) SecondaryButton("Measure here", onMeasure, Modifier.weight(1f))
    }
}

private val SheetGap = 18.dp
private const val MAX_SHEET_FRACTION = 0.72f
private const val NO_DATA = "No data yet"
private const val TOO_MUCH = 1
private const val FINE = 0

/** About two Bluetooth devices per person, as the crowd bands in Category.kt assume. */
private const val DEVICES_PER_PERSON = 2.0

/**
 * Below this flicker index the sheet says "no flicker". A first guess, like the other thresholds:
 * steady LED and daylight measure well under it, old fluorescent tubes over it.
 */
private const val FLICKER_NOTICEABLE = 0.05
