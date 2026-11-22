package ch.ciuin.app.ui.you

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.ciuin.app.core.AppEnvironment
import ch.ciuin.app.core.ListeningStats
import ch.ciuin.app.core.ModelInfo
import ch.ciuin.app.core.ModelVariant
import ch.ciuin.app.ui.characters.Sheep
import ch.ciuin.app.ui.characters.SheepPose
import ch.ciuin.app.ui.components.BarRow
import ch.ciuin.app.ui.components.CiuinTopBar
import ch.ciuin.app.ui.components.IconDisc
import ch.ciuin.app.ui.components.IconNote
import ch.ciuin.app.ui.components.LinkRow
import ch.ciuin.app.ui.components.RadioRow
import ch.ciuin.app.ui.components.RadioState
import ch.ciuin.app.ui.components.SectionLabel
import ch.ciuin.app.ui.components.SharedFactRow
import ch.ciuin.app.ui.components.StatTile
import ch.ciuin.app.ui.components.StatTileStyle
import ch.ciuin.app.ui.components.TitleBlock
import ch.ciuin.app.ui.components.WoolCard
import ch.ciuin.app.ui.components.WoolCardStyle
import ch.ciuin.app.ui.components.WoolPanel
import ch.ciuin.app.ui.icons.CiuinIcons
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinType
import kotlin.math.roundToInt

@Composable
internal fun YouDetailLayout(
    title: String,
    insets: WindowInsets,
    onBack: () -> Unit,
    gap: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(insets), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxSize().padding(horizontal = 24.dp)) {
            CiuinTopBar(title = title, onBack = onBack)
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = gap, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(gap),
                content = content,
            )
        }
    }
}

@Composable
internal fun OnThisPhoneScreen(
    environment: AppEnvironment,
    insets: WindowInsets,
    onBack: () -> Unit,
    onReset: () -> Unit,
) {
    val models = environment.models
    val variants = models.variants
    val active = models.active
    val selected = models.selected
    val displayed = variants.firstOrNull { it.variant == (active ?: selected) && it.available }
    val personal = environment.personalModel

    YouDetailLayout("On this phone", insets, onBack) {
        WoolPanel {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconDisc(CiuinIcons.Chip, size = 44.dp, iconSize = 24.dp, color = CiuinColors.Ground)
                TitleBlock(
                    title = displayed?.let { "Student ${it.variant.name}" } ?: "Sound classification",
                    subtitle = when {
                        displayed != null && active != null -> "Current audio session"
                        displayed != null -> "Chosen for this phone"
                        variants.isEmpty() -> "Model information is unavailable"
                        else -> "Unavailable on this phone"
                    },
                    titleStyle = CiuinType.CardTitle,
                    subtitleStyle = CiuinType.Caption,
                    gap = 0.dp,
                )
            }
            if (displayed != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("${displayed.sizeMb} MB", "Size", Modifier.weight(1f), StatTileStyle.Inner)
                    StatTile(percent(displayed.accuracy), "Accuracy", Modifier.weight(1f), StatTileStyle.Inner)
                    StatTile("${(displayed.parameterCount / 1000.0).roundToInt()}k", "Weights", Modifier.weight(1f), StatTileStyle.Inner)
                }
            }
        }
        if (variants.isNotEmpty()) {
            Column(Modifier.selectableGroup()) {
                SectionLabel("Model size", bottomPadding = 0.dp)
                variants.forEachIndexed { index, model ->
                    val pending = model.variant == selected && active != null && active != selected
                    RadioRow(
                        title = model.variant.sizeLabel,
                        subtitle = if (pending) "Applies after listening and measuring stop" else model.optionSubtitle,
                        state = when {
                            !model.available -> RadioState.Unavailable
                            model.variant == selected -> RadioState.Selected
                            else -> RadioState.Unselected
                        },
                        onClick = { models.select(model.variant) },
                        divider = index != variants.lastIndex,
                    )
                }
            }
        }
        ListeningToday(environment.listening?.todayStats)
        personal.state.session?.let { session ->
            val count = session.exampleCount
            val countText = if (count == 1) "1 saved tap" else "$count saved taps"
            Column {
                SectionLabel("Personal model", bottomPadding = 0.dp)
                LinkRow("Your sheep: $countText", "Reset", onReset, actionEnabled = personal.canResetLearnedSettings)
            }
        }
    }
}

@Composable
private fun ListeningToday(stats: ListeningStats?) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Listening today", bottomPadding = 0.dp)
        when {
            stats == null -> Text("Background listening is unavailable on this phone.", style = CiuinType.BodySmall, color = CiuinColors.SecondaryText)
            stats.totalMillis == 0L -> Text("No background listening activity today.", style = CiuinType.BodySmall, color = CiuinColors.SecondaryText)
            else -> {
                BarRow("Asleep, no model", stats.asleepShare.toFloat(), percent(stats.asleepShare), labelWidth = 150.dp)
                BarRow("Model running", stats.smallModelShare.toFloat(), percent(stats.smallModelShare), labelWidth = 150.dp)
                if (stats.largeModelMillis > 0) {
                    BarRow("Large model", stats.largeModelShare.toFloat(), percent(stats.largeModelShare), labelWidth = 150.dp)
                }
                if (stats.asleepShare > 0.5) {
                    IconNote(
                        icon = CiuinIcons.BatteryFull,
                        text = "Most of the time no model is running.",
                        modifier = Modifier.padding(top = 2.dp),
                        iconSize = 22.dp,
                        gap = 10.dp,
                        style = CiuinType.Caption,
                    )
                }
            }
        }
    }
}

private fun percent(value: Double) = "${(value * 100).roundToInt()}%"

private val ModelVariant.sizeLabel: String
    get() = when (this) {
        ModelVariant.S -> "Small"
        ModelVariant.M -> "Medium"
        ModelVariant.L -> "Large"
    }

private val ModelInfo.optionSubtitle: String
    get() = unavailableReason ?: when (variant) {
        ModelVariant.S -> "$sizeMb MB · lightest on the battery"
        ModelVariant.M -> "$sizeMb MB · for stronger phones"
        ModelVariant.L -> "$sizeMb MB"
    }

@Composable
internal fun WhatIsSharedScreen(insets: WindowInsets, onBack: () -> Unit) {
    YouDetailLayout("What is shared", insets, onBack, gap = 18.dp) {
        Text("Your raw recordings stay here.", Modifier.semantics { heading() }, style = CiuinType.HeadlineTight)
        Column {
            SectionLabel("When you share a measurement")
            SharedFactRow("Average and peak loudness", shared = true)
            SharedFactRow("The mix of sound types, if available", shared = true)
            SharedFactRow("Brightness and flicker, if available", shared = true)
            SharedFactRow("Nearby Bluetooth device count, if available", shared = true)
            SharedFactRow("The place and time", shared = true)
            SharedFactRow("Capture duration, device model and app model version", shared = true)
            SharedFactRow("A random measurement ID for retries", shared = true, divider = false)
        }
        Column {
            SectionLabel("Stays on this phone")
            SharedFactRow("Audio and camera frames", shared = false)
            SharedFactRow("Your Too much and This is fine taps", shared = false)
            SharedFactRow("Your personal model", shared = false)
            SharedFactRow("Bluetooth names and addresses", shared = false, divider = false)
        }
        Column {
            SectionLabel("When you request walking directions")
            SharedFactRow("Start and destination coordinates go to the walking route provider", shared = true, divider = false)
        }
        WoolCard(WoolCardStyle.Note) {
            Sheep(SheepPose.Rest, Modifier.size(72.dp, 66.dp), contentDescription = null, zzz = false)
            Text(
                "The server only accepts measurement summaries and their details.",
                Modifier.weight(1f),
                style = CiuinType.BodySmall,
            )
        }
    }
}
