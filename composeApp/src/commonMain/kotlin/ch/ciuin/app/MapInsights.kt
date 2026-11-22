package ch.ciuin.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.ciuin.app.insight.AreaInsight
import ch.ciuin.app.insight.MeasuredInsight
import ch.ciuin.app.insight.SoundInsight
import ch.ciuin.app.insight.SoundSources
import ch.ciuin.app.insight.walkingMinutes
import ch.ciuin.app.route.QuietSpot
import ch.ciuin.app.ui.characters.Sheep
import ch.ciuin.app.ui.characters.SheepPose
import ch.ciuin.app.ui.components.BarRow
import ch.ciuin.app.ui.components.BigReading
import ch.ciuin.app.ui.components.CharacterTile
import ch.ciuin.app.ui.components.IconDisc
import ch.ciuin.app.ui.components.InfoTile
import ch.ciuin.app.ui.components.Level
import ch.ciuin.app.ui.components.LevelGlyph
import ch.ciuin.app.ui.components.LevelGlyphSize
import ch.ciuin.app.ui.components.LevelLegend
import ch.ciuin.app.ui.components.PrimaryButton
import ch.ciuin.app.ui.components.SecondaryButton
import ch.ciuin.app.ui.components.SecondaryButtonSize
import ch.ciuin.app.ui.components.SegmentedControl
import ch.ciuin.app.ui.components.TitleBlock
import ch.ciuin.app.ui.icons.CiuinIcon
import ch.ciuin.app.ui.icons.CiuinIcons
import ch.ciuin.app.ui.place.dbText
import ch.ciuin.app.ui.place.distanceText
import ch.ciuin.app.ui.place.personalLevel
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinType
import ch.ciuin.app.ui.toUi
import ch.ciuin.shared.api.NoisePeriod
import ch.ciuin.shared.model.Place
import kotlin.math.roundToInt

private val personalLegend = listOf(
    Level.Calm to "Calm", Level.Moderate to "Moderate", Level.Loud to "Too much", Level.NoData to "No data",
)

@Composable
internal fun MapInsights(
    category: Category,
    insight: AreaInsight,
    noisePeriod: NoisePeriod,
    onNoisePeriod: (NoisePeriod) -> Unit,
    offline: Boolean,
    pending: Int,
    canListen: Boolean,
    nearest: QuietSpot?,
    onPick: (Place) -> Unit,
    onMeasure: (() -> Unit)?,
    liveFeedback: ((Int) -> Unit)?,
    feedbackEnabled: Boolean,
    showFlicker: Boolean,
) {
    when (category) {
        Category.PERSONAL -> {
            val personal = insight.personal
            val sound = personal.meanDb?.let { mean ->
                val source = when (personal.soundSources) {
                    SoundSources.Baseline -> "outdoor estimate"
                    SoundSources.Measurements -> "measured"
                    SoundSources.Both -> "measurements and estimates"
                    null -> null
                }
                listOfNotNull(dbText(mean), source).joinToString(" · ")
            }
            val readings = listOfNotNull(
                sound,
                personal.lightLevel?.let { Category.LIGHT.levelName(it).lowercase() + " light" },
                personal.crowdLevel?.let { Category.CROWD.levelName(it).lowercase() + " crowd" },
            ).joinToString(" · ")
            val level = personal.overwhelm?.level.toUi()
            val title = when (level) {
                Level.Calm -> "Calm for you"
                Level.Moderate -> "Moderate for you"
                Level.Loud -> "May be too much"
                Level.NoData -> "No prediction here yet"
            }
            val label = if (level == Level.NoData) "Around you" else "Around you · personal model"
            if (offline) {
                TitleBlock(
                    title, eyebrow = "$label · offline",
                    subtitle = readings.ifEmpty {
                        if (canListen) "Listening and alerts still work without a connection." else null
                    },
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CharacterTile {
                        Sheep(
                            if (level == Level.Calm) SheepPose.Rest else SheepPose.Awake,
                            Modifier.size(62.dp, 56.dp), contentDescription = null, zzz = false,
                        )
                    }
                    TitleBlock(
                        title, Modifier.weight(1f), eyebrow = label,
                        subtitle = readings.ifEmpty { "Tap a place to see what's known." },
                    )
                }
                LevelLegend(personalLegend)
            }
            if (pending > 0 && offline) {
                val label = if (pending == 1) "1 measurement waiting" else "$pending measurements waiting"
                InfoTile(label, "Keep Ciúin open until you're back online") { CiuinIcon(CiuinIcons.Clock01, null) }
            } else if (nearest != null) {
                val place = nearest.place
                val details = listOfNotNull(
                    distanceText(nearest.distanceMeters)?.let { distance ->
                        "$distance · about ${walkingMinutes(nearest.distanceMeters)} min walk"
                    },
                    "open now".takeIf { place.openNow == true },
                ).joinToString(" · ")
                InfoTile("Walk to ${place.name}", "Nearest quiet spot · $details", onClick = { onPick(place) }) {
                    LevelGlyph(personalLevel(nearest.predictedOverwhelm))
                }
            }
        }
        Category.SOUND -> SoundPanel(insight.sound, noisePeriod, onNoisePeriod)
        Category.LIGHT -> if (showFlicker) FlickerPanel(insight.meanFlickerIndex, onMeasure)
        else MeasuredPanel("Light", insight.light, category, onMeasure, insight.meanFlickerIndex)
        Category.CROWD -> MeasuredPanel("Crowd", insight.crowd, category, onMeasure)
        Category.ACCESS -> {
            val access = insight.access
            val shares = access.shares
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconDisc(CiuinIcons.Wheelchair)
                TitleBlock(
                    title = shares?.let { "${percent(it.accessible)} wheelchair accessible" } ?: "No access tags here",
                    modifier = Modifier.weight(1f),
                    subtitle = shares?.let {
                        if (access.taggedPlaces == 1) "From 1 tagged place" else "From ${access.taggedPlaces} tagged places"
                    },
                )
            }
            if (shares != null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ShareRow("Accessible", shares.accessible, Level.Calm)
                    ShareRow("Limited", shares.limited, Level.Moderate)
                    ShareRow("Not accessible", shares.notAccessible, Level.Loud)
                }
            }
            Text(
                "Empty areas have no tagged places. Tap a place to add what you know.",
                style = CiuinType.Caption,
                color = CiuinColors.SecondaryText,
            )
        }
    }
    if (liveFeedback != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                "Too much", { liveFeedback(1) }, Modifier.weight(1f),
                SecondaryButtonSize.Small, enabled = feedbackEnabled,
            )
            SecondaryButton(
                "This is fine", { liveFeedback(0) }, Modifier.weight(1f),
                SecondaryButtonSize.Small, enabled = feedbackEnabled,
            )
        }
    }
}

@Composable
private fun SoundPanel(sound: SoundInsight, period: NoisePeriod, onPeriod: (NoisePeriod) -> Unit) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("This area · Sound", style = CiuinType.Eyebrow, color = CiuinColors.SecondaryText)
            val reading = sound.noise
            if (reading == null) Text("No sound data", style = CiuinType.Subtitle)
            else BigReading(dbText(reading.mean), Category.SOUND.levelName(reading.level))
        }
        SegmentedControl(listOf("Day", "Night"), period.ordinal, { onPeriod(NoisePeriod.entries[it]) })
    }
    LevelLegend(listOf(Level.Calm to "Quiet, under 50", Level.Moderate to "Moderate", Level.Loud to "Loud, over 65"))
    if (sound.roadMeanDb != null || sound.railMeanDb != null) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Where it comes from", style = CiuinType.Label)
            sound.roadMeanDb?.let { SourceBar("Road", it) }
            sound.railMeanDb?.let { SourceBar("Rail", it) }
        }
    }
    sound.measuredSoundTypes?.let { soundTypes ->
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(soundTypes.heading, style = CiuinType.Label)
            soundTypes.shares.forEach { share ->
                BarRow(share.label, share.fraction.toFloat(), percent(share.fraction), labelWidth = 132.dp)
            }
        }
    }
}

@Composable
private fun SourceBar(label: String, db: Double) {
    BarRow(label, Category.SOUND.heat(db).toFloat(), dbText(db), labelWidth = 48.dp, valueWidth = 48.dp, gap = 12.dp)
}

@Composable
private fun MeasuredPanel(
    name: String,
    insight: MeasuredInsight,
    category: Category,
    onMeasure: (() -> Unit)?,
    flickerIndex: Double? = null,
) {
    val isLight = category == Category.LIGHT
    if (insight is MeasuredInsight.Data) {
        Text("This area · $name", style = CiuinType.Eyebrow, color = CiuinColors.SecondaryText)
        BigReading(
            if (isLight) "${insight.mean.roundToInt()} lux" else "${insight.mean.roundToInt()} devices",
            category.levelName(insight.level),
        )
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CharacterTile(size = 84.dp, cornerRadius = 28.dp) {
                Sheep(SheepPose.Reading, Modifier.size(72.dp), contentDescription = null)
            }
            TitleBlock(
                title = if (isLight) "No brightness readings yet" else "No crowd readings yet",
                modifier = Modifier.weight(1f),
                eyebrow = "This area · $name",
                subtitle = if (isLight) "Light and flicker come from phones. A 30-second measure can add a reading."
                else "Nearby Bluetooth devices give an estimate of how busy a place is.",
            )
        }
    }
    flickerIndex?.let {
        Text("Measured flicker index ${it.fmt2()}", style = CiuinType.BodySmall, color = CiuinColors.SecondaryText)
    }
    LevelLegend(listOf(
        Level.Calm to if (isLight) "Dim" else "Quiet",
        Level.Moderate to if (isLight) "Moderate" else "Busy",
        Level.Loud to if (isLight) "Bright" else "Crowded",
        Level.NoData to "No data",
    ))
    if (onMeasure != null && insight == MeasuredInsight.NoData) {
        PrimaryButton("Measure where I am", onMeasure, Modifier.fillMaxWidth())
    }
}

internal fun flickerLevel(index: Double): Level = when {
    index < 0.05 -> Level.Calm
    index < 0.2 -> Level.Moderate
    else -> Level.Loud
}

@Composable
private fun FlickerPanel(index: Double?, onMeasure: (() -> Unit)?) {
    if (index != null) {
        Text("This area · Flicker", style = CiuinType.Eyebrow, color = CiuinColors.SecondaryText)
        BigReading(index.fmt2(), "Measured flicker index")
    } else {
        TitleBlock("No flicker readings yet", eyebrow = "This area · Flicker", subtitle = "A camera measurement can add a reading.")
    }
    LevelLegend(listOf(Level.Calm to "Low", Level.Moderate to "Moderate", Level.Loud to "High", Level.NoData to "No data"))
    if (index == null && onMeasure != null) PrimaryButton("Measure where I am", onMeasure, Modifier.fillMaxWidth())
}

@Composable
private fun ShareRow(label: String, value: Double, level: Level) {
    BarRow(label, value.toFloat(), percent(value)) {
        LevelGlyph(level, size = LevelGlyphSize.Small, lane = 16.dp)
    }
}

private fun percent(share: Double): String = "${(share * 100).roundToInt()}%"
