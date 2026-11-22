package ch.ciuin.app.ui.route

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.ciuin.app.core.AppEnvironment
import ch.ciuin.app.insight.walkingMinutes
import ch.ciuin.app.route.CompassHeading
import ch.ciuin.app.route.ManeuverKind
import ch.ciuin.app.route.QuietPlaceRoute
import ch.ciuin.app.route.RouteStep
import ch.ciuin.app.route.WalkingRoute
import ch.ciuin.app.route.routeStepPhrase
import ch.ciuin.app.ui.characters.Sheep
import ch.ciuin.app.ui.characters.SheepPose
import ch.ciuin.app.ui.components.BottomSheetSurface
import ch.ciuin.app.ui.components.CharacterTile
import ch.ciuin.app.ui.components.ListRow
import ch.ciuin.app.ui.components.RouteStepRow
import ch.ciuin.app.ui.components.SecondaryButton
import ch.ciuin.app.ui.components.SecondaryButtonSize
import ch.ciuin.app.ui.components.TitleBlock
import ch.ciuin.app.ui.components.WalkingBanner
import ch.ciuin.app.ui.icons.CiuinIcon
import ch.ciuin.app.ui.icons.CiuinIcons
import ch.ciuin.app.ui.place.dbText
import ch.ciuin.app.ui.place.distanceText
import ch.ciuin.app.ui.place.maxHeightFraction
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinType
import ch.ciuin.shared.model.Place
import androidx.compose.material3.Text
import kotlin.math.roundToInt

/**
 * The black "Walking to …" banner the map shows at the top while a route is active: the walking
 * distance and minutes once the route is in, the straight-line distance until then or when no route
 * was found. The map gives it the 16 dp margin and the top inset.
 */
@Composable
internal fun RouteHeader(
    route: QuietPlaceRoute,
    walkingRoute: WalkingRoute?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val subtitle = if (walkingRoute != null) {
        val minutes = walkingRoute.durationSeconds?.takeIf { it.isFinite() && it > 0 }
            ?.let { (it / 60).roundToInt().coerceAtLeast(1) }
            ?: walkingMinutes(walkingRoute.distanceMeters)
        listOfNotNull(distanceText(walkingRoute.distanceMeters), "$minutes min").joinToString(" · ")
    } else {
        distanceText(route.distanceMeters)?.let { "$it in a straight line" } ?: "Finding the way"
    }
    WalkingBanner("Walking to ${route.destinationName}", subtitle, onClose, modifier)
}

/**
 * The bottom sheet the map shows while a route is active: "Getting calmer", the turn-by-turn steps,
 * "End route" and "I'm okay now". [destination] is the place being walked to when the map knows it.
 *
 * The first step is the one to take next and is set in medium weight. There is no live position on the
 * route, so the steps do not advance by themselves. The step list scrolls once the sheet would be taller
 * than about 72% of the height its parent offers.
 */
@Composable
internal fun RouteSheet(
    environment: AppEnvironment,
    route: QuietPlaceRoute,
    walkingRoute: WalkingRoute?,
    loading: Boolean,
    destination: Place?,
    onEnd: () -> Unit,
    onOkayNow: () -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    val name = route.destinationName
    val hereDb = environment.listening?.liveDb
    val thereDb = destination?.takeIf { it.placeId == route.destinationId }?.measured?.features?.dbMean
    val calmer = when {
        hereDb != null && thereDb != null -> "${dbText(hereDb)} here, ${dbText(thereDb)} at $name"
        thereDb != null -> "${dbText(thereDb)} measured at $name"
        hereDb != null -> "${dbText(hereDb)} here. Take it slowly."
        else -> "Take it slowly. You can stop any time."
    }

    BottomSheetSurface(modifier.maxHeightFraction(0.72f), bottomPadding = 8.dp, bottomInset = bottomInset) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CharacterTile { Sheep(SheepPose.Awake, Modifier.size(56.dp), contentDescription = null) }
            TitleBlock("Getting calmer", Modifier.weight(1f), subtitle = calmer)
        }
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())) {
            val steps = walkingRoute?.steps.orEmpty()
            when {
                steps.isNotEmpty() -> steps.forEachIndexed { i, step ->
                    val (icon, iconSize) = step.glyph()
                    RouteStepRow(
                        icon = icon,
                        text = routeStepPhrase(step, name),
                        distance = step.distanceMeters.takeIf { it >= 1 }?.let { distanceText(it) }.orEmpty(),
                        current = i == 0,
                        iconSize = iconSize,
                        divider = i != steps.lastIndex,
                    )
                }
                walkingRoute == null && loading -> NoteRow(CiuinIcons.Walking, "Finding a walking route…")
                walkingRoute != null -> NoteRow(
                    CiuinIcons.Location01,
                    "Follow the line to $name",
                    distanceText(walkingRoute.distanceMeters),
                )
                else -> NoteRow(
                    CiuinIcons.Navigation03,
                    "No walking route found. The line on the map goes straight to $name.",
                    distanceText(route.distanceMeters),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("End route", onEnd, Modifier.weight(1f), SecondaryButtonSize.Medium)
            SecondaryButton("I'm okay now", onOkayNow, Modifier.weight(1f), SecondaryButtonSize.Medium)
        }
    }
}

/** A single line in place of the steps: loading, or no route. Laid out like a step row, but it may wrap. */
@Composable
private fun NoteRow(icon: ImageVector, text: String, distance: String? = null) {
    ListRow(
        minHeight = 48.dp,
        verticalPadding = 4.dp,
        divider = false,
        leadingLane = 24.dp,
        leading = { CiuinIcon(icon, null, size = 24.dp) },
        trailing = distance?.let { { Text(it, style = CiuinType.Caption, color = CiuinColors.SecondaryText) } },
    ) { Text(text, style = CiuinType.BodyMedium) }
}

/**
 * The icon of a step and its size: the turn's direction, the pin for the arrival, the walker for going
 * straight on. A departure points left or right only when its compass heading is roughly west or east,
 * as on the artboard ("Head west" with a left arrow).
 */
private fun RouteStep.glyph(): Pair<ImageVector, Dp> = when (kind) {
    ManeuverKind.TURN_LEFT, ManeuverKind.SLIGHT_LEFT, ManeuverKind.SHARP_LEFT -> Pair(CiuinIcons.ArrowLeft01, 24.dp)
    ManeuverKind.TURN_RIGHT, ManeuverKind.SLIGHT_RIGHT, ManeuverKind.SHARP_RIGHT -> Pair(CiuinIcons.ArrowRight01, 24.dp)
    ManeuverKind.ARRIVE_LEFT, ManeuverKind.ARRIVE_RIGHT, ManeuverKind.ARRIVE_AHEAD -> Pair(CiuinIcons.Location01, 22.dp)
    ManeuverKind.DEPART -> when (heading) {
        CompassHeading.WEST, CompassHeading.NORTHWEST, CompassHeading.SOUTHWEST -> Pair(CiuinIcons.ArrowLeft01, 24.dp)
        CompassHeading.EAST, CompassHeading.NORTHEAST, CompassHeading.SOUTHEAST -> Pair(CiuinIcons.ArrowRight01, 24.dp)
        else -> Pair(CiuinIcons.Walking, 24.dp)
    }
    ManeuverKind.CONTINUE -> Pair(CiuinIcons.Walking, 24.dp)
    ManeuverKind.U_TURN -> Pair(CiuinIcons.Refresh, 24.dp)
}
