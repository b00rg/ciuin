package ch.ciuin.app.ui.alert

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.ciuin.app.Category
import ch.ciuin.app.core.ActiveAlert
import ch.ciuin.app.core.AppEnvironment
import ch.ciuin.app.insight.walkingMinutes
import ch.ciuin.app.ui.characters.AlertScene
import ch.ciuin.app.ui.components.Level
import ch.ciuin.app.ui.components.PrimaryButton
import ch.ciuin.app.ui.components.QuietPlaceCard
import ch.ciuin.app.ui.components.SecondaryButton
import ch.ciuin.app.ui.components.SecondaryButtonSize
import ch.ciuin.app.ui.icons.CiuinIcons
import ch.ciuin.app.ui.place.dbText
import ch.ciuin.app.ui.place.distanceText
import ch.ciuin.app.ui.place.durationInWords
import ch.ciuin.app.ui.place.minutesInWords
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinMotion
import ch.ciuin.app.ui.theme.CiuinType
import ch.ciuin.app.ui.theme.LocalReduceMotion
import ch.ciuin.app.ui.toUi

/**
 * Full-screen "It's getting loud here", shown while [AppEnvironment.alerts] has an active alert.
 * [mapPreview] fills the map area of the quiet-place card (the map screen supplies it).
 *
 * It fades in and rises 12 dp over 280 ms when a new alert appears (a 150 ms fade under Reduce motion),
 * and the sheep lifts its ears once. Nothing flashes or loops. Without a suggestion there is no card and
 * no "Take me there". The screen pads itself by the safe-drawing insets.
 */
@Composable
internal fun AlertScreen(
    environment: AppEnvironment,
    alert: ActiveAlert,
    mapPreview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alerts = environment.alerts
    val suggestion = alert.suggestion
    val reduceMotion = LocalReduceMotion.current
    val appear = remember(alert.id) { Animatable(0f) }
    LaunchedEffect(alert.id) {
        appear.animateTo(1f, if (reduceMotion) CiuinMotion.reducedFade() else CiuinMotion.alertAppear())
    }

    val pill = alert.currentDb?.let { "Too much · ${dbText(it)}" } ?: "Too much"
    val lasted = "Above your limit for ${durationInWords(alert.aboveLimitMillis)}."
    val placeDb = suggestion?.let { it.measuredDb ?: it.priorDb }
    val placeLevel = placeDb?.let { Category.SOUND.levelOf(it) }.toUi()
    val body = if (suggestion == null) {
        "$lasted A few minutes somewhere quieter can help."
    } else {
        val kind = if (placeLevel == Level.Calm) "a calm place" else "a quieter place"
        "$lasted There's $kind ${minutesInWords(walkingMinutes(suggestion.distanceMeters))} away."
    }

    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .graphicsLayer {
                alpha = appear.value
                if (!reduceMotion) translationY = (1f - appear.value) * CiuinMotion.ALERT_RISE_DP.dp.toPx()
            },
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            AlertScene(pill)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("It's getting loud here.", Modifier.semantics { heading() }, style = CiuinType.ScreenTitle)
                Text(body, style = CiuinType.Body, color = CiuinColors.SecondaryText)
            }
            if (suggestion != null) {
                val facts = listOfNotNull(
                    distanceText(suggestion.distanceMeters),
                    "${walkingMinutes(suggestion.distanceMeters)} min walk",
                    when (suggestion.openNow) {
                        true -> "open now"
                        false -> "closed"
                        null -> null
                    },
                )
                QuietPlaceCard(
                    level = placeLevel,
                    title = if (placeLevel == Level.NoData) suggestion.name else "${suggestion.name} · ${placeLevel.word}",
                    subtitle = facts.joinToString(" · "),
                    // A measurement is shown as it is; the place's estimate says it is one.
                    value = when {
                        suggestion.measuredDb != null -> dbText(suggestion.measuredDb)
                        suggestion.priorDb != null -> "about ${dbText(suggestion.priorDb)}"
                        else -> ""
                    },
                    onClick = alerts::takeMeThere,
                ) { mapPreview() }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (suggestion != null) {
                PrimaryButton("Take me there", alerts::takeMeThere, Modifier.fillMaxWidth(), leadingIcon = CiuinIcons.Navigation03)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("I'm okay here", alerts::okayHere, Modifier.weight(1f), SecondaryButtonSize.Medium)
                SecondaryButton("Snooze 15 min", { alerts.snooze(15) }, Modifier.weight(1f), SecondaryButtonSize.Medium)
            }
        }
    }
}
