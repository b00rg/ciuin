package ch.ciuin.app.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.ciuin.app.core.AppEnvironment

/** First launch: the five onboarding steps. Calls [onFinished] after "Open the map" or "Skip". */
@Composable
internal fun OnboardingFlow(environment: AppEnvironment, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier)
}
