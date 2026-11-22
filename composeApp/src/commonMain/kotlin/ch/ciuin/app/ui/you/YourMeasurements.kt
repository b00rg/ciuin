@file:OptIn(kotlin.time.ExperimentalTime::class)

package ch.ciuin.app.ui.you

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ch.ciuin.app.core.MeasurementRecord
import ch.ciuin.app.ui.characters.Sheep
import ch.ciuin.app.ui.characters.SheepPose
import ch.ciuin.app.ui.components.CharacterTile
import ch.ciuin.app.ui.components.Label
import ch.ciuin.app.ui.components.ListRow
import ch.ciuin.app.ui.components.TitleBlock
import ch.ciuin.app.ui.doodles.Doodle
import ch.ciuin.app.ui.icons.CiuinIcon
import ch.ciuin.app.ui.icons.CiuinIcons
import ch.ciuin.app.ui.measure.devicesText
import ch.ciuin.app.ui.measure.luxText
import ch.ciuin.app.ui.measure.whole
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinType
import ch.ciuin.shared.model.Place
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Every measurement the user shared, newest first, with the numbers that were sent. A row with a
 * place opens it on the map; a "Somewhere else" row opens the spot it was measured at.
 */
@Composable
internal fun YourMeasurementsScreen(
    history: List<MeasurementRecord>,
    places: List<Place>,
    insets: WindowInsets,
    onBack: () -> Unit,
    onOpenPlace: (Place) -> Unit,
    onOpenSpot: (lat: Double, lon: Double) -> Unit,
) {
    val byId = remember(places) { places.associateBy { it.placeId } }
    YouDetailLayout("Your measurements", insets, onBack) {
        if (history.isEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                CharacterTile(doodle = Doodle.Sparkle) { Sheep(SheepPose.Reading, Modifier.size(56.dp), contentDescription = null) }
                TitleBlock(
                    title = "No measurements yet",
                    modifier = Modifier.weight(1f),
                    subtitle = "Measure a place and it shows up here, with the readings you shared.",
                    titleStyle = CiuinType.Subtitle,
                )
            }
            return@YouDetailLayout
        }
        Text(
            "The readings you shared, newest first. Tap one to see it on the map.",
            style = CiuinType.BodySmall,
            color = CiuinColors.SecondaryText,
        )
        val now = Clock.System.now()
        val zone = TimeZone.currentSystemDefault()
        Column {
            history.forEachIndexed { index, record ->
                val place = record.placeId?.let(byId::get)
                val title = place?.name ?: record.placeName ?: if (record.placeId == null) "Somewhere else" else "A place on the map"
                val open = when {
                    place != null -> { { onOpenPlace(place) } }
                    else -> { { onOpenSpot(record.lat, record.lon) } }
                }
                ListRow(
                    modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Show on the map", onClick = open),
                    minHeight = 72.dp,
                    verticalPadding = 12.dp,
                    divider = index != history.lastIndex,
                    trailing = { CiuinIcon(CiuinIcons.ArrowRight01, null, size = 20.dp, tint = CiuinColors.SecondaryText) },
                ) {
                    Label(title, CiuinType.RowTitle)
                    Label(measuredWhen(record.timestamp, now, zone), CiuinType.Caption, color = CiuinColors.SecondaryText)
                    Label(readingsLine(record), CiuinType.BodySmall)
                }
            }
        }
    }
}

/** "57 dB · 82 lux · 3 devices": the numbers that were sent, as on the screen after sharing. */
internal fun readingsLine(record: MeasurementRecord): String {
    val parts = buildList {
        record.dbMean?.let { add("${whole(it)} dB") }
        record.luxMean?.let { add(luxText(it)) }
        record.nearbyDevices?.let { add(devicesText(it)) }
        if (isEmpty()) add(record.flickerHz?.let { "${whole(it)} Hz flicker" } ?: "Sound categories only")
    }
    return parts.joinToString(" · ")
}

/** "Today, 14:20", "Yesterday, 09:05" or "22 Nov 2026, 14:20", in the phone's time zone. */
internal fun measuredWhen(timestamp: String, now: Instant, zone: TimeZone): String {
    val at = runCatching { Instant.parse(timestamp) }.getOrNull() ?: return timestamp
    val local = at.toLocalDateTime(zone)
    val today = now.toLocalDateTime(zone).date
    val time = "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
    val day = when (local.date) {
        today -> "Today"
        LocalDate.fromEpochDays(today.toEpochDays() - 1) -> "Yesterday"
        else -> "${local.date.day} ${MONTHS[local.date.month.ordinal]} ${local.date.year}"
    }
    return "$day, $time"
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
