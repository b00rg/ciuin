@file:OptIn(kotlin.time.ExperimentalTime::class)

package ch.ciuin.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.ciuin.app.core.AppEnvironment
import ch.ciuin.app.insight.PlaceIndex
import ch.ciuin.app.insight.categoryLabel
import ch.ciuin.app.insight.placeHours
import ch.ciuin.app.route.RoutePoint
import ch.ciuin.app.route.distanceMeters
import ch.ciuin.app.ui.characters.Sheep
import ch.ciuin.app.ui.characters.SheepPose
import ch.ciuin.app.ui.components.CharacterTile
import ch.ciuin.app.ui.components.PlaceResultRow
import ch.ciuin.app.ui.components.RecentRow
import ch.ciuin.app.ui.components.SearchField
import ch.ciuin.app.ui.components.SectionLabel
import ch.ciuin.app.ui.components.TextLinkButton
import ch.ciuin.app.ui.components.TitleBlock
import ch.ciuin.app.ui.place.distanceText
import ch.ciuin.app.ui.place.forYouWord
import ch.ciuin.app.ui.place.hoursText
import ch.ciuin.app.ui.place.personalLevel
import ch.ciuin.app.ui.theme.CiuinType
import ch.ciuin.shared.model.Place
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Full-screen search opened from the map's search bar. [personalScores] is predicted overwhelm by place id
 * (the "For you" rating shown on each match). [onPick] gets the chosen match or recent place.
 *
 * The field is focused with the keyboard up when the screen opens. An empty query shows only the recent
 * places; the place index is built once per [places] list, so each keystroke only ranks matches.
 * Picking a place also adds it to the recent places.
 */
@Composable
internal fun SearchScreen(
    environment: AppEnvironment,
    places: List<Place>,
    personalScores: Map<String, Double>,
    onPick: (Place) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SearchScreen(environment, places, personalScores, onPick, onCancel, modifier, initialQuery = "", now = null)
}

/** [initialQuery] and [now] are parameters so tests can render a typed query at a pinned time. */
@Composable
internal fun SearchScreen(
    environment: AppEnvironment,
    places: List<Place>,
    personalScores: Map<String, Double>,
    onPick: (Place) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
    initialQuery: String,
    now: Instant?,
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    val index = remember(places) { PlaceIndex(places) }
    val byId = remember(places) { places.associateBy { it.placeId } }
    val origin = environment.location.current
    val matches = remember(index, query, origin) { index.searchPlaces(query, origin, limit = MATCH_LIMIT) }
    val recents = environment.settings.recentPlaces.mapNotNull { byId[it.placeId] }
    val at = remember(query, now) { now ?: Clock.System.now() }

    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    val pick: (Place) -> Unit = { place ->
        environment.settings.rememberPlace(place.placeId, place.name)
        keyboard?.hide()
        onPick(place)
    }

    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                focusRequester = focus,
                onSearch = { matches.firstOrNull()?.let(pick) },
            )
            TextLinkButton("Cancel", {
                keyboard?.hide()
                onCancel()
            })
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val typed = query.isNotBlank()
            if (typed && matches.isNotEmpty()) {
                Column {
                    SectionLabel("Matches")
                    matches.forEachIndexed { i, place ->
                        val level = personalLevel(personalScores[place.placeId])
                        val facts = listOfNotNull(
                            categoryLabel(place.category).takeIf { it.isNotBlank() },
                            origin?.let { distanceText(distanceMeters(it, RoutePoint(place.lat, place.lon))) },
                            hoursText(placeHours(place, at), withClosingTime = false),
                        )
                        PlaceResultRow(
                            level = level,
                            title = place.name,
                            subtitle = facts.joinToString(" · "),
                            onClick = { pick(place) },
                            levelWord = level.forYouWord,
                            divider = i != matches.lastIndex,
                        )
                    }
                }
            } else if (typed) {
                NoMatch(query.trim())
            }
            if (recents.isNotEmpty()) {
                Column {
                    SectionLabel("Recent")
                    recents.forEachIndexed { i, place ->
                        RecentRow(place.name, { pick(place) }, divider = i != recents.lastIndex)
                    }
                }
            }
        }
    }
}

/** Nothing matched: the reading sheep, and a hint of what does work. */
@Composable
private fun NoMatch(query: String) {
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharacterTile { Sheep(SheepPose.Reading, Modifier.size(56.dp), contentDescription = null) }
        TitleBlock(
            title = "Nothing called “$query” yet",
            modifier = Modifier.weight(1f),
            subtitle = "Try another spelling, or a kind of place, like café or library.",
            titleStyle = CiuinType.Subtitle,
        )
    }
}

private const val MATCH_LIMIT = 10
