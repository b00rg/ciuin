package ch.ciuin.app.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.ciuin.app.core.AppEnvironment
import ch.ciuin.app.ui.characters.AllSetScene
import ch.ciuin.app.ui.characters.WelcomeScene
import ch.ciuin.app.ui.components.CiuinTopBar
import ch.ciuin.app.ui.components.NumberedStep
import ch.ciuin.app.ui.components.PageDots
import ch.ciuin.app.ui.components.PrimaryButton
import ch.ciuin.app.ui.components.TextLinkButton
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinMotion
import ch.ciuin.app.ui.theme.CiuinType
import ch.ciuin.app.ui.theme.LocalReduceMotion
import ch.ciuin.shared.personal.PersonalPreset

private enum class OnboardingPage(val button: String, val gap: Int) {
    Welcome("Get started", 28),
    HowItReads("Continue", 28),
    StartingPoint("Continue", 20),
    Permissions("Continue", 20),
    AllSet("Open the map", 24),
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun OnboardingFlow(
    environment: AppEnvironment,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    var choice by rememberSaveable { mutableStateOf<PersonalPreset?>(null) }
    val page = OnboardingPage.entries[index]
    val reduceMotion = LocalReduceMotion.current
    val personalModel = environment.personalModel
    val saving = page == OnboardingPage.StartingPoint && personalModel.state.busy
    val finish = {
        environment.settings.onboardingComplete = true
        onFinished()
    }
    val back: () -> Unit = { if (index > 0) index-- }
    BackHandler(enabled = index > 0, onBack = back)

    Column(
        modifier.fillMaxSize().background(CiuinColors.Ground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        CiuinTopBar(
            wordmark = page == OnboardingPage.Welcome,
            onBack = back.takeUnless { page == OnboardingPage.Welcome },
            trailing = if (page == OnboardingPage.AllSet) null else ({
                TextLinkButton("Skip", finish, color = CiuinColors.SecondaryText, textAlign = TextAlign.End)
            }),
        )
        key(page) {
            val appear = remember { Animatable(0f) }
            LaunchedEffect(reduceMotion) {
                appear.animateTo(
                    1f,
                    if (reduceMotion) CiuinMotion.reducedFade() else tween(220, easing = CiuinMotion.EaseOut),
                )
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .graphicsLayer {
                        alpha = appear.value
                        translationY = if (reduceMotion) 0f else (1f - appear.value) * 8.dp.toPx()
                    }
                    .padding(top = page.gap.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(page.gap.dp),
            ) {
                when (page) {
                    OnboardingPage.Welcome -> {
                        WelcomeScene()
                        OnboardingCopy(
                            "Find your quiet.",
                            "Ciúin maps how loud, bright and busy places are, so you can choose where to go before you get there.",
                            hero = true,
                        )
                    }
                    OnboardingPage.HowItReads -> {
                        HowItReadsScene()
                        OnboardingCopy(
                            "See how a place feels at a glance.",
                            "Every area gets a colour, a shape and a word. Switch between sound, light, crowd and access.",
                        )
                    }
                    OnboardingPage.StartingPoint -> {
                        OnboardingCopy(
                            "What bothers you most?",
                            "Pick the closest fit. Your map starts here and keeps learning from you.",
                        )
                        StartingPointPicker(
                            selected = choice ?: personalModel.state.session?.preset ?: PersonalPreset.AUTISM_FRIENDLY,
                            onSelect = { choice = it },
                            enabled = !saving,
                        )
                    }
                    OnboardingPage.Permissions -> OnboardingPermissions(environment)
                    OnboardingPage.AllSet -> {
                        AllSetScene()
                        OnboardingCopy("You're all set.", "Three things to know before you go.", hero = true)
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            NumberedStep(1, "Tap any place to see how it feels.")
                            NumberedStep(2, "Tell the sheep when it's too much.")
                            NumberedStep(3, "Measure a place and a duck joins in.")
                        }
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (page == OnboardingPage.StartingPoint) {
                Text(
                    "You can change this any time in You.",
                    style = CiuinType.BodySmall,
                    color = CiuinColors.SecondaryText,
                    textAlign = TextAlign.Center,
                )
            }
            PageDots(OnboardingPage.entries.size, index)
            PrimaryButton(
                page.button,
                onClick = {
                    when (page) {
                        OnboardingPage.AllSet -> finish()
                        OnboardingPage.StartingPoint -> {
                            choice?.let(personalModel::selectPreset)
                            index = page.ordinal + 1
                        }
                        else -> index = page.ordinal + 1
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving,
            )
        }
    }
}

@Composable
private fun OnboardingCopy(title: String, subtitle: String, hero: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(if (hero) 12.dp else 10.dp)) {
        Text(title, Modifier.semantics { heading() }, style = if (hero) CiuinType.Hero else CiuinType.ScreenTitle)
        Text(subtitle, style = CiuinType.Body, color = CiuinColors.SecondaryText)
    }
}
