package ch.ciuin.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.ciuin.app.ui.theme.CiuinColors
import kotlinx.coroutines.flow.Flow

private const val WAVEFORM_BARS = 48

/**
 * The last ~5 s of loudness as a quiet row of rounded bars, newest on the right: shows the phone is listening and
 * where the spikes are. Calm on purpose: the sage accent on the ring's track colour, no glow, no colour changes.
 */
@Composable
internal fun SoundWaveform(levels: Flow<Float>, modifier: Modifier = Modifier) {
    val bars = remember { mutableStateListOf<Float>().apply { repeat(WAVEFORM_BARS) { add(0f) } } }
    LaunchedEffect(levels) {
        levels.collect { level ->
            bars.removeAt(0)
            bars.add(level)
        }
    }
    Canvas(
        modifier.height(44.dp).semantics { contentDescription = "Live sound level" },
    ) {
        val slot = size.width / WAVEFORM_BARS
        val barWidth = slot * 0.55f
        val minHeight = barWidth
        val mid = size.height / 2
        bars.forEachIndexed { i, level ->
            val h = minHeight + (size.height - minHeight) * level
            val x = i * slot + (slot - barWidth) / 2
            drawRoundRect(
                color = if (level > 0f) CiuinColors.Accent else CiuinColors.RingTrack,
                topLeft = Offset(x, mid - h / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}
