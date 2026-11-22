package ch.ciuin.app.ui.measure

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinSpacing
import ch.ciuin.app.ui.theme.CiuinType

/*
 * The frame the four Measure screens share, from the artboards: 24 dp side margins, content that
 * starts 8 dp under the top inset (16 on "Shared") and scrolls if a small phone runs out of room, and
 * a footer of buttons 8 dp above the bottom inset.
 */

/**
 * A full Measure screen inside the safe drawing area. With [bottomInset] false the screen leaves the
 * bottom inset to whatever it draws last (the tab bar on the start screen).
 */
@Composable
internal fun MeasureFrame(
    modifier: Modifier = Modifier,
    bottomInset: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sides = if (bottomInset) WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical else WindowInsetsSides.Horizontal + WindowInsetsSides.Top
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(sides)), content = content)
}

/** The scrolling middle of a screen, with the artboard's gap between blocks. */
@Composable
internal fun ColumnScope.MeasureContent(
    top: Dp = 8.dp,
    gap: Dp = CiuinSpacing.Section,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = CiuinSpacing.Screen, end = CiuinSpacing.Screen, top = top, bottom = 16.dp),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** The buttons at the bottom of a screen. */
@Composable
internal fun MeasureFooter(gap: Dp, bottom: Dp = 8.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = CiuinSpacing.Screen, end = CiuinSpacing.Screen, bottom = bottom),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** A screen title over its grey line: "Measure / Pick your place. It takes 30 seconds." */
@Composable
internal fun MeasureCopyBlock(title: String, body: String?, gap: Dp = CiuinSpacing.Copy) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        Text(title, Modifier.semantics { heading() }, style = CiuinType.ScreenTitle)
        if (body != null) Text(body, style = CiuinType.Body, color = CiuinColors.SecondaryText)
    }
}
