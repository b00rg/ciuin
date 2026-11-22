package ch.ciuin.app.ui.components

import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.ciuin.app.ui.icons.CiuinIcon
import ch.ciuin.app.ui.icons.CiuinIconStroke
import ch.ciuin.app.ui.icons.CiuinIcons
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinElevation
import ch.ciuin.app.ui.theme.CiuinRadii
import ch.ciuin.app.ui.theme.CiuinType
import ch.ciuin.app.ui.theme.ciuinShadow

/**
 * The grey handle at the top of a sheet: 36 by 4, centred in a 24 dp strip. Decoration only; a sheet
 * that opens and closes uses [SheetToggle] in its place, and a fixed one [SheetTopSpace].
 */
@Composable
fun DragHandle(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(HandleStrip), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 36.dp, height = 4.dp).background(CiuinColors.Handle, CircleShape))
    }
}

/**
 * The white surface of a bottom sheet: 28 dp top corners, a drag handle 10 dp from the top, and an
 * upward shadow. It lays its [content] out in a column.
 *
 * - Map sheets (For you, Sound, Light, Access): the defaults, with the tab bar below the sheet.
 * - Offline sheet: `gap = 14`.
 * - Place sheet: `gap = 18`, `bottomPadding = 8`; route sheet: `bottomPadding = 8`. Both reach the
 *   bottom edge of the screen, so pass the navigation-bar inset as [bottomInset].
 * - Reset confirmation: `horizontalPadding = 24`, `gap = 20`, `bottomPadding = 8`, `shadow = false`
 *   (it sits on a [Scrim]).
 *
 * This is only the surface. Sliding it in (320 ms spring, `CiuinMotion.sheetSpring`) and dragging it
 * are up to the screen.
 *
 * @param bottomInset height of the system navigation area under the sheet, added below the content.
 * @param handle what sits in the 24 dp strip at the top: a [DragHandle] by default, a [SheetToggle] on
 *   the map's layer sheets, or [SheetTopSpace] on the fixed For you sheet.
 */
@Composable
fun BottomSheetSurface(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 20.dp,
    gap: Dp = 16.dp,
    bottomPadding: Dp = 18.dp,
    bottomInset: Dp = 0.dp,
    shadow: Boolean = true,
    handle: @Composable () -> Unit = { DragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(topStart = CiuinRadii.Sheet, topEnd = CiuinRadii.Sheet)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (shadow) Modifier.ciuinShadow(CiuinElevation.Sheet, shape) else Modifier)
            // Clipped to its own corners so the toggle's press highlight follows the rounded top.
            .clip(shape)
            .background(CiuinColors.Ground, shape)
            .padding(bottom = bottomPadding + bottomInset),
    ) {
        // The handle strip spans the whole sheet, edge to edge; only the content is inset. The bar sits
        // 10 dp from the top and the content [gap] below it, as before the strip grew.
        handle()
        Spacer(Modifier.height(gap - (HandleStrip - 4.dp) / 2))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(gap),
            content = content,
        )
    }
}

/**
 * The arrow at the top of a sheet that can hide its details: down while they show, up while they are
 * hidden. A wide, shallow chevron in the handle's grey, where the bar would be. The whole 24 dp strip,
 * from one edge of the sheet to the other, is the button.
 */
@Composable
fun SheetToggle(expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(HandleStrip + ToggleTopRoom)
            .clickable(
                onClickLabel = if (expanded) "Hide details to see the map" else "Show details",
                role = Role.Button,
                onClick = onToggle,
            )
            .semantics { stateDescription = if (expanded) "Details shown" else "Details hidden" }
            // Room above the arrow, inside the button so its press highlight still reaches the top edge.
            .padding(top = ToggleTopRoom),
        contentAlignment = Alignment.Center,
    ) {
        CiuinIcon(
            if (expanded) CiuinIcons.ArrowDown01 else CiuinIcons.ArrowUp01,
            contentDescription = null,
            size = 32.dp,
            tint = CiuinColors.Handle,
            strokeWidth = CiuinIconStroke.SheetToggle,
        )
    }
}

/**
 * The empty top of a map sheet that does not open and close (For you): as tall as a [SheetToggle],
 * so the content sits at the same height on every layer.
 */
@Composable
fun SheetTopSpace() {
    Spacer(Modifier.fillMaxWidth().height(HandleStrip + ToggleTopRoom))
}

/** Extra space above a [SheetToggle]'s arrow, so it does not crowd the sheet's top edge. */
private val ToggleTopRoom = 8.dp

/** Height of the handle's strip; the 4 dp bar is centred in it, 10 dp from the top. */
private val HandleStrip = 24.dp

/**
 * The dark layer behind a confirmation sheet (ink at 40%), covering the whole screen. Tapping it
 * calls [onDismiss].
 */
@Composable
fun Scrim(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(CiuinColors.Scrim)
            .clickable(interactionSource = null, indication = null, onClickLabel = "Dismiss", onClick = onDismiss),
    )
}

/**
 * The black banner: one message that overrides the screen, with an icon, two lines and one action
 * on the right. Use [OfflineBanner] or [WalkingBanner] unless you need a third kind.
 */
@Composable
fun InkBanner(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    action: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CiuinColors.Ink, RoundedCornerShape(if (large) CiuinRadii.Card else CiuinRadii.Option))
            .padding(
                start = if (large) 16.dp else 14.dp,
                end = if (large) 12.dp else 8.dp,
                top = if (large) 14.dp else 10.dp,
                bottom = if (large) 14.dp else 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CiuinIcon(icon, null, size = if (large) 26.dp else 22.dp, tint = CiuinColors.OnInk)
        Column(Modifier.weight(1f)) {
            Label(title, if (large) CiuinType.Subtitle else CiuinType.Label, color = CiuinColors.OnInk)
            Label(subtitle, CiuinType.Caption, color = CiuinColors.OnInkSecondary)
        }
        action()
    }
}

/**
 * "You're offline", under the layer chips on the map, with a white "Retry" pill. Give it the 16 dp
 * map margin and 12 dp above it.
 */
@Composable
fun OfflineBanner(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "You're offline",
    subtitle: String = "Showing the map saved on this phone",
    retryLabel: String = "Retry",
) {
    InkBanner(CiuinIcons.WifiOff01, title, subtitle, modifier) { BannerButton(retryLabel, onRetry) }
}

/**
 * The header while walking to a quiet place: "Walking to Library", distance and time, and a round
 * white close button. It takes the place of the search row at the top of the map, with the 16 dp
 * map margin.
 */
@Composable
fun WalkingBanner(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    closeDescription: String = "End route",
) {
    InkBanner(CiuinIcons.Walking, title, subtitle, modifier, large = true) {
        RoundIconButton(CiuinIcons.Cancel01, closeDescription, onClose, style = RoundIconButtonStyle.OnInk)
    }
}

/** The three tabs of the app. */
enum class CiuinTab(val label: String, internal val icon: ImageVector) {
    Map("Map", CiuinIcons.Maps),
    Measure("Measure", CiuinIcons.AudioWave01),
    You("You", CiuinIcons.User),
}

/**
 * The bottom tab bar: Map, Measure, You. White with a hairline on top; each tab is an icon over its
 * word, 64 high. The selected tab is black with a heavier icon and label, the others grey.
 *
 * @param bottomInset height of the system navigation area; the bar's white extends under it.
 */
@Composable
fun CiuinTabBar(
    selected: CiuinTab,
    onSelect: (CiuinTab) -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CiuinColors.Ground)
            .topHairline(CiuinColors.HairlineStrong)
            .padding(top = 1.dp, bottom = bottomInset)
            .height(64.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (tab in CiuinTab.entries) {
            val isSelected = tab == selected
            val color = if (isSelected) CiuinColors.Ink else CiuinColors.SecondaryText
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
                    .semantics { this.selected = isSelected }
                    .clickable(interactionSource = null, indication = null, role = Role.Tab) { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            ) {
                CiuinIcon(
                    icon = tab.icon,
                    contentDescription = null,
                    tint = color,
                    strokeWidth = if (isSelected) CiuinIconStroke.Selected else CiuinIconStroke.Regular,
                )
                Label(tab.label, if (isSelected) CiuinType.TabSelected else CiuinType.Tab, color = color, maxLines = 1)
            }
        }
    }
}

/**
 * The 48-high row at the top of a screen. It does not include the status bar: place it below the
 * top safe-area inset.
 *
 * - Inner screens (model, privacy): a back arrow and a 20/28 [title].
 * - Onboarding 2 to 5: a back arrow, no title, and "Skip" as the [trailing] `TextLinkButton`.
 * - Onboarding 1 and the globe: [wordmark] instead of the back arrow.
 *
 * Give it the screen's 24 dp side margin. The arrow's 48 dp target starts at the margin, so the icon
 * itself lines up with the text below.
 */
@Composable
fun CiuinTopBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    backDescription: String = "Back",
    wordmark: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            RoundIconButton(CiuinIcons.ArrowLeft01, backDescription, onBack, style = RoundIconButtonStyle.Plain)
        }
        if (wordmark) Wordmark()
        if (title != null) {
            Label(title, CiuinType.Title, Modifier.weight(1f).semantics { heading() }, maxLines = 1)
        } else {
            Box(Modifier.weight(1f))
        }
        if (trailing != null) trailing()
    }
}

/** The app's name as it is written in the top bar: "ciúin", lower case, 20/28 semibold. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Label("ciúin", CiuinType.Wordmark, modifier.clearAndSetSemantics { contentDescription = "Ciúin" })
}

/**
 * The centred heading of the measuring screen, in place of a top bar title: a grey eyebrow
 * ("Measuring") over the place name. 48 high like the top bar.
 */
@Composable
fun CenteredHeading(eyebrow: String, title: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().height(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Label(eyebrow, CiuinType.Eyebrow, color = CiuinColors.SecondaryText, maxLines = 1)
        Label(title, CiuinType.Subtitle, Modifier.semantics { heading() }, maxLines = 1)
    }
}

/**
 * The white veil that fades the top of the map out under the status bar, search row and chips.
 * 210 high on the map (add the difference if the status bar is not 62), 270 with the offline
 * banner, 170 under the walking header. [fadeStart] is where the solid part begins to fade: 0.55 on
 * the map, 0.6 offline, 0.5 walking.
 */
@Composable
fun MapVeil(modifier: Modifier = Modifier, height: Dp = 210.dp, fadeStart: Float = 0.55f) {
    Box(
        modifier.fillMaxWidth().height(height).background(
            Brush.verticalGradient(
                0f to CiuinColors.VeilTop,
                fadeStart to CiuinColors.VeilMiddle,
                1f to CiuinColors.VeilBottom,
            ),
        ),
    )
}

/**
 * The map credit, on a translucent white plate at the bottom left of the map, 8 dp above the sheet:
 * "© OpenStreetMap · noise: sonBASE © FOEN". The smallest text in the app (11/16).
 */
@Composable
fun AttributionPlate(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(CiuinColors.AttributionPlate, RoundedCornerShape(CiuinRadii.Plate))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Label(text, CiuinType.Attribution, color = CiuinColors.SecondaryText, maxLines = 1)
    }
}

/**
 * The marker at the end of a walking route: the destination's level colour on a white disc with a
 * hairline edge, 30 across.
 */
@Composable
fun DestinationDot(level: Level, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(30.dp)
            .background(CiuinColors.Ground, CircleShape)
            .border(1.dp, CiuinColors.FloatingOutline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(16.dp).background(level.color, CircleShape))
    }
}

/** How the walking route is drawn on the map: a 3 dp black line of round dots, 1 on and 8 off. */
object RouteLineStyle {
    val Width = 3.dp
    val Dash = 1.dp
    val Gap = 8.dp
}
