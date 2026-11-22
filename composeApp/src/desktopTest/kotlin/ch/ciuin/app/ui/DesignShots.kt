package ch.ciuin.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinTheme
import java.io.File
import kotlin.math.ceil
import org.jetbrains.skia.EncodedImageFormat

/**
 * Renders composables to PNG files without a device or a window, so a screen can be checked against
 * its Paper export.
 *
 * Every shot is drawn inside [CiuinTheme] on the white ground, in Poppins, at density 2: one dp is
 * two pixels, the same scale as the 2x exports in the design folder, so the two images can be laid
 * over each other. Files go to `composeApp/build/design-shots/`.
 *
 * To produce the shots, run the tests that call this (from the repository root):
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "ch.ciuin.app.ui.*"
 * ```
 *
 * To add a screen, write a test that calls [screen]:
 *
 * ```
 * @Test fun mapHome() { DesignShots.screen("map-home") { MapHomeScreen(previewState) } }
 * ```
 *
 * The artboards include a 62 dp status bar and a home indicator that are device chrome, not app UI.
 * Pass the same insets to your screen (or leave that space empty) when you compare.
 *
 * Paper exports its PNGs in Display P3, while these shots are sRGB. Convert an export before
 * comparing pixel values, or every saturated colour will seem about 10 levels off:
 * `sips -m "/System/Library/ColorSync/Profiles/sRGB Profile.icc" export.png --out export-srgb.png`.
 * Compared that way, the replicas in `PaperScreens.kt` of the artboards without a map differ from
 * Paper by about 1/255 on average, all of it at the edges of text.
 *
 * Animations are driven by a virtual clock: `atMillis` is how far into the screen's life the frame
 * is taken (0 is the first frame), and `reduceMotion` turns the theme's Reduce motion flag on.
 * `density` is 2 unless you are comparing with a 1x export (the Foundations and Motion boards).
 */
object DesignShots {
    /** The size of every phone artboard in Paper. */
    const val SCREEN_WIDTH = 390
    const val SCREEN_HEIGHT = 844

    /** Pixels per dp, matching the 2x exports. */
    const val DENSITY = 2f

    val directory: File = File("build/design-shots")

    /** Renders [content] as a full screen, [width] by [height] dp, to `<name>.png`. */
    fun screen(
        name: String,
        width: Int = SCREEN_WIDTH,
        height: Int = SCREEN_HEIGHT,
        atMillis: Int = 0,
        reduceMotion: Boolean = false,
        density: Float = DENSITY,
        dark: Boolean = false,
        content: @Composable () -> Unit,
    ): File = render(name, width, height, atMillis, reduceMotion, density, dark) {
        Box(Modifier.fillMaxSize().background(CiuinColors.Ground)) { content() }
    }

    /**
     * Renders [content] [width] dp wide and exactly as tall as it lays out, to `<name>.png`. For a
     * single component, a sheet, or a gallery section.
     */
    fun fitted(
        name: String,
        width: Int = SCREEN_WIDTH,
        atMillis: Int = 0,
        reduceMotion: Boolean = false,
        density: Float = DENSITY,
        dark: Boolean = false,
        content: @Composable () -> Unit,
    ): File {
        var measuredPx = 0
        val wrapped: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize().background(CiuinColors.Ground), contentAlignment = Alignment.TopStart) {
                Box(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).onSizeChanged { measuredPx = it.height }) {
                    content()
                }
            }
        }
        // One throwaway frame to learn the height, then the real render at that height.
        scene(width, MEASURE_HEIGHT, reduceMotion, density, dark, wrapped).use { it.render(0) }
        val height = ceil(measuredPx / density).toInt().coerceAtLeast(1)
        return render(name, width, height, atMillis, reduceMotion, density, dark, wrapped)
    }

    private fun render(
        name: String,
        width: Int,
        height: Int,
        atMillis: Int,
        reduceMotion: Boolean,
        density: Float,
        dark: Boolean,
        content: @Composable () -> Unit,
    ): File {
        val file = File(directory, "$name.png").apply { parentFile.mkdirs() }
        scene(width, height, reduceMotion, density, dark, content).use { scene ->
            // Step the virtual clock so one-shot animations start and chain as they would on a phone.
            var millis = 0
            var image = scene.render(0)
            while (millis < atMillis) {
                millis = minOf(millis + FRAME_MILLIS, atMillis)
                image = scene.render(millis * 1_000_000L)
            }
            file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        return file
    }

    private fun scene(width: Int, height: Int, reduceMotion: Boolean, density: Float, dark: Boolean, content: @Composable () -> Unit) =
        ImageComposeScene(
            width = (width * density).toInt(),
            height = (height * density).toInt(),
            density = Density(density),
        ) {
            // Pinned, so a computer in dark mode does not change the light shots.
            CiuinTheme(reduceMotion = reduceMotion, dark = dark, content = content)
        }

    private const val MEASURE_HEIGHT = 6000
    private const val FRAME_MILLIS = 16
}
