package ch.ciuin.app.ui.you

import ch.ciuin.shared.model.Place
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.ciuin.app.core.AppEnvironment
import ch.ciuin.app.personal.PersonalModelController
import ch.ciuin.app.ui.characters.PondScene
import ch.ciuin.app.ui.characters.SheepPose
import ch.ciuin.app.ui.components.BottomSheetSurface
import ch.ciuin.app.ui.components.DestructiveButton
import ch.ciuin.app.ui.components.DestructiveRow
import ch.ciuin.app.ui.components.IconDisc
import ch.ciuin.app.ui.components.PrimaryButton
import ch.ciuin.app.ui.components.Scrim
import ch.ciuin.app.ui.components.SettingsRow
import ch.ciuin.app.ui.components.SettingsSwitchRow
import ch.ciuin.app.ui.components.TextLinkButton
import ch.ciuin.app.ui.icons.CiuinIcons
import ch.ciuin.app.ui.onboarding.StartingPointPicker
import ch.ciuin.app.ui.onboarding.startingPointTitle
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinMotion
import ch.ciuin.app.ui.theme.CiuinType
import ch.ciuin.app.ui.theme.LocalReduceMotion

private enum class YouPage { Root, StartingPoint, OnThisPhone, WhatIsShared, Measurements }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun YouTab(
    environment: AppEnvironment,
    tabBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    places: List<Place> = emptyList(),
    onOpenPlace: (Place) -> Unit = {},
    onOpenSpot: (lat: Double, lon: Double) -> Unit = { _, _ -> },
) {
    val insets = WindowInsets.safeDrawing
    var page by rememberSaveable { mutableStateOf(YouPage.Root) }
    var resetOpen by rememberSaveable { mutableStateOf(false) }
    val back = { page = YouPage.Root }
    val openReset = { resetOpen = true }
    val closeReset = { resetOpen = false }
    val personal = environment.personalModel

    BackHandler(enabled = resetOpen || page != YouPage.Root) {
        if (resetOpen) closeReset() else back()
    }

    Box(modifier.fillMaxSize()) {
        Crossfade(
            targetState = page,
            modifier = if (resetOpen) Modifier.clearAndSetSemantics {} else Modifier,
            animationSpec = if (LocalReduceMotion.current) CiuinMotion.reducedFade() else tween(200),
            label = "you-page",
        ) { current ->
            when (current) {
                YouPage.Root -> YouScreen(environment, tabBar, insets, { page = it }, openReset)
                YouPage.StartingPoint -> StartingPointScreen(personal, insets, back)
                YouPage.OnThisPhone -> OnThisPhoneScreen(environment, insets, back, openReset)
                YouPage.WhatIsShared -> WhatIsSharedScreen(insets, back)
                YouPage.Measurements -> YourMeasurementsScreen(
                    environment.settings.measurementHistory, places, insets, back, onOpenPlace, onOpenSpot,
                )
            }
        }
        ResetSheet(resetOpen, personal, insets, closeReset)
    }
}

@Composable
private fun YouScreen(
    environment: AppEnvironment,
    tabBar: @Composable () -> Unit,
    insets: WindowInsets,
    onOpen: (YouPage) -> Unit,
    onReset: () -> Unit,
) {
    val settings = environment.settings
    val listening = environment.listening
    val personal = environment.personalModel.state
    val listeningOn = listening?.isListening == true

    Column(Modifier.fillMaxSize().windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("You", Modifier.semantics { heading() }, style = CiuinType.ScreenTitle)
                Column {
                    val measured = settings.measurementHistory.size
                    SettingsRow(
                        title = "Your measurements",
                        subtitle = when (measured) {
                            0 -> "Readings you share show up here"
                            1 -> "1 reading shared"
                            else -> "$measured readings shared"
                        },
                        onClick = { onOpen(YouPage.Measurements) },
                    )
                    SettingsRow(
                        title = "Starting point",
                        subtitle = personal.session?.preset?.startingPointTitle,
                        onClick = { onOpen(YouPage.StartingPoint) },
                    )
                    SettingsSwitchRow(
                        title = "Listen in the background",
                        subtitle = if (listening == null) "Unavailable on this phone" else "Wakes only when it gets loud",
                        checked = listeningOn,
                        onCheckedChange = { listening?.setListening(it) },
                        enabled = listening != null,
                    )
                    listening?.message?.let { message ->
                        Text(message, Modifier.padding(top = 4.dp, bottom = 8.dp), style = CiuinType.Caption, color = CiuinColors.SecondaryText)
                    }
                    SettingsSwitchRow(
                        title = "Gentle alerts",
                        subtitle = if (listening == null) "Unavailable on this phone" else "One vibration, then a calm place",
                        checked = listening != null && settings.gentleAlerts,
                        onCheckedChange = { settings.gentleAlerts = it },
                        enabled = listening != null,
                    )
                    SettingsSwitchRow(
                        title = "Reduce motion",
                        subtitle = "Sheep and ducks hold still",
                        checked = settings.reduceMotion,
                        onCheckedChange = { settings.reduceMotion = it },
                    )
                    SettingsRow("What is shared", "Readings are sent, taps stay here", { onOpen(YouPage.WhatIsShared) })
                    SettingsRow("On this phone", "Listening model and activity", { onOpen(YouPage.OnThisPhone) })
                    DestructiveRow("Reset what the sheep learned", onReset, enabled = environment.personalModel.canResetLearnedSettings)
                }
                PondScene(settings.sharedMeasurements, pose = if (listeningOn) SheepPose.Awake else SheepPose.Rest)
            }
        }
        tabBar()
    }
}

@Composable
private fun StartingPointScreen(personalModel: PersonalModelController, insets: WindowInsets, onBack: () -> Unit) {
    val state = personalModel.state
    YouDetailLayout("Starting point", insets, onBack) {
        Text("What bothers you most?", Modifier.semantics { heading() }, style = CiuinType.ScreenTitle)
        Text("Pick the closest fit. Your map starts here and keeps learning from you.", style = CiuinType.Body, color = CiuinColors.SecondaryText)
        if (state.session == null) {
            if (state.busy) {
                Text("Loading your starting point…", style = CiuinType.BodySmall, color = CiuinColors.SecondaryText)
            } else {
                TextLinkButton("Retry loading", personalModel::load)
            }
        } else {
            StartingPointPicker(state.session.preset, personalModel::selectPreset, enabled = !state.busy)
        }
    }
}

@Composable
private fun ResetSheet(
    visible: Boolean,
    personalModel: PersonalModelController,
    insets: WindowInsets,
    onDismiss: () -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val state = personalModel.state
    val count = state.session?.exampleCount ?: 0
    val title = "Reset what the sheep learned?"

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(CiuinMotion.reducedFade()),
        exit = fadeOut(CiuinMotion.reducedFade()),
    ) {
        Box(Modifier.fillMaxSize()) {
            Scrim(onDismiss)
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                val transition = if (reduceMotion) Modifier else Modifier.animateEnterExit(
                    enter = slideInVertically(CiuinMotion.sheetSpring()) { it },
                    exit = slideOutVertically(tween(200)) { it },
                )
                BottomSheetSurface(
                    modifier = transition.widthIn(max = 520.dp)
                        .semantics { paneTitle = title }
                        .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                        .verticalScroll(rememberScrollState()),
                    horizontalPadding = 24.dp,
                    gap = 20.dp,
                    bottomPadding = 8.dp,
                    bottomInset = insets.asPaddingValues().calculateBottomPadding(),
                    shadow = false,
                ) {
                    IconDisc(CiuinIcons.Delete02, iconSize = 26.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(title, Modifier.semantics { heading() }, style = CiuinType.PlaceTitle)
                        val detail = when (count) {
                            0 -> "This goes back to A bit of everything. Your ducks stay."
                            1 -> "Your sheep forgets your one answer and goes back to A bit of everything. Your ducks stay."
                            else -> "Your sheep forgets your $count answers and goes back to A bit of everything. Your ducks stay."
                        }
                        Text(detail, style = CiuinType.Body, color = CiuinColors.SecondaryText)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Keep what it learned", onDismiss, Modifier.fillMaxWidth())
                        DestructiveButton(
                            text = "Reset",
                            onClick = {
                                personalModel.resetLearnedSettings()
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = personalModel.canResetLearnedSettings,
                        )
                    }
                }
            }
        }
    }
}
