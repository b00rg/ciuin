package ch.ciuin.app.ui.icons

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Stroke widths the design uses, on the 24 grid. */
object CiuinIconStroke {
    /** Every icon unless noted. */
    const val Regular = 1.5f

    /** The icon of the selected tab, and the tick in a chosen "This is fine" button. */
    const val Selected = 2f

    /** Ticks in checklists and the privacy list (16 to 20 dp). */
    const val Check = 2.2f

    /** The arrow that hides and shows a map sheet's details (32 dp): about 4 dp, as thick as the handle bar it replaced. */
    const val SheetToggle = 3f

    /** The tick inside a filled radio mark and in the "Allowed" pill (16 dp). */
    const val CheckBold = 2.5f
}

/**
 * Draws one of [CiuinIcons] at [size], tinted with [tint].
 *
 * Icons scale with their stroke, as on the artboards: a 20 dp icon has a 1.25 dp line. Pass a
 * [strokeWidth] from [CiuinIconStroke] where the design thickens it. Give a [contentDescription]
 * unless the icon sits next to a word that says the same thing.
 */
@Composable
fun CiuinIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
    strokeWidth: Float = CiuinIconStroke.Regular,
) {
    val vector = remember(icon, strokeWidth) { icon.withStrokeWidth(strokeWidth) }
    Icon(imageVector = vector, contentDescription = contentDescription, modifier = modifier.size(size), tint = tint)
}

/** The same icon with every stroke redrawn [width] wide (in 24-grid units). */
fun ImageVector.withStrokeWidth(width: Float): ImageVector {
    if (width == CiuinIconStroke.Regular) return this
    val builder = ImageVector.Builder(
        name = "$name@$width",
        defaultWidth = defaultWidth,
        defaultHeight = defaultHeight,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
    )
    for (node in root) {
        if (node !is VectorPath) continue
        builder.addPath(
            pathData = node.pathData,
            pathFillType = node.pathFillType,
            fill = node.fill,
            stroke = node.stroke,
            strokeLineWidth = if (node.stroke != null) width else node.strokeLineWidth,
            strokeLineCap = node.strokeLineCap,
            strokeLineJoin = node.strokeLineJoin,
        )
    }
    return builder.build()
}
