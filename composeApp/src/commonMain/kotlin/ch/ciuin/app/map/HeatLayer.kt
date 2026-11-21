package ch.ciuin.app.map

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import ch.ciuin.app.heat.HeatCell
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Heatmap as an image covering the data: top-left at normalised ([x], [y]), each pixel [pixelSize] wide
 * in normalised Web Mercator coords (see [MapState]). [MapView] stretches it over the map.
 */
class HeatLayer(val image: ImageBitmap, val x: Double, val y: Double, val pixelSize: Double)

/** Transparent border (in cells) around the data. */
private const val PAD = 1

/** Image pixels per cell: higher = smoother band edges, more work. */
private const val UPSCALE = 12

/** Below this interpolated data weight a pixel counts as "no data", which gives the data area a clean edge. */
private const val MIN_WEIGHT = 0.5

/** Safety cap on the image size (pixels); Lausanne at the finest level is ~1700 × 1100. */
private const val MAX_PIXELS = 4_000_000

/**
 * Builds banded regions ("defined sections") from aggregated cells:
 * the cell values ([value] → e.g. heat in [0, 1]) are interpolated smoothly between cell centres,
 * then every pixel is coloured by its band ([band] → a solid colour). So the borders between
 * Quiet / Moderate / Loud follow smooth curves instead of cell edges, and stay crisp.
 */
fun buildHeatLayer(cells: List<HeatCell>, value: (Double) -> Double, band: (Double) -> Color): HeatLayer? {
    if (cells.isEmpty()) return null
    val size = cells[0].size
    val ix = LongArray(cells.size) { (cells[it].x / size).roundToLong() }
    val iy = LongArray(cells.size) { (cells[it].y / size).roundToLong() }
    val minX = ix.min() - PAD
    val minY = iy.min() - PAD
    val w = (ix.max() + PAD - minX + 1).toInt()
    val h = (iy.max() + PAD - minY + 1).toInt()
    val iw = w * UPSCALE
    val ih = h * UPSCALE
    if (iw.toLong() * ih > MAX_PIXELS) return null

    // Cell grid: value and data weight (0 = no data).
    val vals = DoubleArray(w * h)
    val wts = DoubleArray(w * h)
    for (i in cells.indices) {
        val k = (iy[i] - minY).toInt() * w + (ix[i] - minX).toInt()
        vals[k] = value(cells[i].mean)
        wts[k] = 1.0
    }

    val image = ImageBitmap(iw, ih)
    val canvas = Canvas(image)
    val paint = Paint()
    val row = arrayOfNulls<Color>(iw)

    for (py in 0 until ih) {
        // Pixel centre in cell units, where cell centres sit at whole numbers.
        val v = (py + 0.5) / UPSCALE - 0.5
        val cy = floor(v).toInt()
        val fy = v - cy
        for (px in 0 until iw) {
            val u = (px + 0.5) / UPSCALE - 0.5
            val cx = floor(u).toInt()
            val fx = u - cx
            var vs = 0.0
            var ws = 0.0
            for (dy in 0..1) for (dx in 0..1) {
                val gx = cx + dx
                val gy = cy + dy
                if (gx < 0 || gy < 0 || gx >= w || gy >= h) continue
                val k = gy * w + gx
                val bilinear = (if (dx == 0) 1 - fx else fx) * (if (dy == 0) 1 - fy else fy)
                vs += vals[k] * wts[k] * bilinear
                ws += wts[k] * bilinear
            }
            row[px] = if (ws < MIN_WEIGHT) null else band(vs / ws)
        }
        // Draw the row as runs of equal colour (far fewer draw calls than one per pixel).
        var start = 0
        while (start < iw) {
            val c = row[start]
            var end = start + 1
            while (end < iw && row[end] == c) end++
            if (c != null) {
                paint.color = c
                canvas.drawRect(start.toFloat(), py.toFloat(), end.toFloat(), py + 1f, paint)
            }
            start = end
        }
    }
    return HeatLayer(image, minX * size, minY * size, size / UPSCALE)
}
