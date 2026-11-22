package ch.ciuin.app.ui

import androidx.compose.runtime.Composable
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the artboard replicas in `PaperScreens.kt` to `composeApp/build/design-shots/paper/`, named
 * after the Paper exports (`03-onboarding-3-starting-point.png` and so on) so each pair can be laid
 * over the other. Characters are drawn in their still poses, as the artboards draw them. The same
 * screens in the dark palette go to `paper-dark/`.
 */
class PaperScreensTest {
    @Test
    fun rendersEveryArtboard() = renderAll(dark = false)

    @Test
    fun rendersEveryArtboardInDarkMode() = renderAll(dark = true)

    private fun renderAll(dark: Boolean) {
        val shot = { name: String, content: @Composable () -> Unit ->
            val folder = if (dark) "paper-dark" else "paper"
            val file = DesignShots.screen("$folder/$name", reduceMotion = true, dark = dark, content = content)
            assertTrue(file.length() > 2_000, "${file.name} is empty")
        }
        shot("01-onboarding-1-welcome") { Onboarding1() }
        shot("03-onboarding-3-starting-point") { Onboarding3() }
        shot("04-onboarding-4-privacy-permissions") { Onboarding4() }
        shot("05-onboarding-5-all-set") { Onboarding5() }
        shot("06-globe-opening") { Globe() }
        shot("08-map-sound-layer") { MapSound() }
        shot("09-map-light-layer-no-data") { MapLightNoData() }
        shot("10-map-access-layer") { MapAccess() }
        shot("10b-map-crowd-layer") { MapCrowd() }
        shot("10c-map-light-layer") { MapLight() }
        shot("11-map-offline") { MapOffline() }
        shot("12-search") { Search() }
        shot("13-place-detail-sheet") { PlaceSheet() }
        shot("14-measure-start") { MeasureStart() }
        shot("15-measure-1-capturing") { MeasureCapturing() }
        shot("16-measure-2-review-share") { MeasureReview() }
        shot("17-measure-3-shared") { MeasureShared() }
        shot("18-alert-above-limit") { Alert() }
        shot("19-route-walking") { Route() }
        shot("20-you-sheep-pond-settings") { You() }
        shot("21-you-reset-confirmation") { ResetSheet() }
        shot("22-model-on-this-phone") { Model() }
        shot("23-privacy-what-is-shared") { Privacy() }
    }
}
