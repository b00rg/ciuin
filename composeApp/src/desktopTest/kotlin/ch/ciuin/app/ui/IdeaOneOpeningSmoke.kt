package ch.ciuin.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import ch.ciuin.app.core.AppPermission
import ch.ciuin.app.core.AppSettings
import ch.ciuin.app.core.FakePermissions
import ch.ciuin.app.core.PermissionStatus
import ch.ciuin.app.core.previewAppEnvironment
import ch.ciuin.app.ui.globe.GlobeOpening
import ch.ciuin.app.ui.onboarding.OnboardingFlow
import ch.ciuin.app.ui.theme.CiuinTheme
import ch.ciuin.shared.api.FakePlacesApi
import ch.ciuin.shared.api.PlacesApi
import ch.ciuin.shared.api.Stats
import ch.ciuin.shared.model.Coverage
import ch.ciuin.shared.model.CoverageCell
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeaOneOpeningSmoke {
    private val shots = File(DesignShots.directory, "opening-flow").apply { mkdirs() }
    private val insets = Modifier.padding(top = 62.dp, bottom = 34.dp)

    @Test
    fun finishAllFiveOnboardingScreens() {
        val environment = previewAppEnvironment(settingsData = AppSettings())
        val permissions = environment.permissions as FakePermissions
        permissions.setStatus(AppPermission.CAMERA, PermissionStatus.NOT_DETERMINED)
        permissions.setStatus(AppPermission.LOCATION, PermissionStatus.NOT_DETERMINED)
        permissions.setStatus(AppPermission.NOTIFICATIONS, PermissionStatus.NOT_DETERMINED)
        var finished = 0
        ImageComposeScene(width = 780, height = 1688, density = Density(2f)) {
            CiuinTheme(reduceMotion = true) { OnboardingFlow(environment, { finished++ }, insets) }
        }.use { scene ->
            scene.shot("01-welcome", 0)
            scene.tap(195, 774, 20)
            scene.shot("02-how-it-reads", 150)
            scene.tap(44, 94, 180)
            scene.shot("01-returned-via-back", 310)
            scene.tap(195, 774, 340)
            scene.tap(195, 774, 480)
            scene.shot("03-starting-point", 615)
            scene.tap(195, 774, 645)
            scene.shot("04-permissions", 785)
            scene.tap(318, 512, 815)
            scene.shot("04-permissions-after-tap", 955)
            scene.tap(195, 774, 985)
            scene.shot("05-all-set", 1_125)
            assertFalse(environment.settings.onboardingComplete)
            scene.tap(195, 774, 1_155)
            assertTrue(environment.settings.onboardingComplete)
            assertEquals(1, finished)
        }
    }

    @Test
    fun skipAndEnterMapFromAnInteractiveGlobe() {
        val api = object : PlacesApi by FakePlacesApi() {
            override suspend fun stats() = Stats(2873, 148, 143)
            override suspend fun coverage(resDeg: Double) = Coverage(resDeg, listOf(
                CoverageCell(47.0, 7.0, measurements = 75),
                CoverageCell(49.0, 3.0, measurements = 24),
                CoverageCell(44.0, 10.0, measurements = 31),
                CoverageCell(43.0, 4.0, measurements = 18),
            ))
        }
        val environment = previewAppEnvironment(api = api, settingsData = AppSettings())
        var finished = 0
        ImageComposeScene(width = 780, height = 1688, density = Density(2f)) {
            CiuinTheme(reduceMotion = true) { OnboardingFlow(environment, { finished++ }, insets) }
        }.use { scene ->
            scene.render(0)
            scene.tap(348, 94, 25)
        }
        assertTrue(environment.settings.onboardingComplete)
        assertEquals(1, finished)
        var entered = 0
        ImageComposeScene(width = 780, height = 1688, density = Density(2f)) {
            CiuinTheme(reduceMotion = true) { GlobeOpening(environment, { entered++ }, insets) }
        }.use { scene ->
            scene.render(0)
            scene.shot("06-globe", 150)
            scene.sendPointerEvent(PointerEventType.Press, Offset(390f, 600f), type = PointerType.Touch, timeMillis = 180)
            scene.render(190_000_000)
            scene.sendPointerEvent(PointerEventType.Move, Offset(470f, 600f), type = PointerType.Touch, timeMillis = 210)
            scene.render(220_000_000)
            scene.sendPointerEvent(PointerEventType.Move, Offset(605f, 620f), type = PointerType.Touch, timeMillis = 240)
            scene.render(250_000_000)
            scene.sendPointerEvent(PointerEventType.Release, Offset(605f, 620f), type = PointerType.Touch, timeMillis = 270)
            scene.shot("06-globe-dragged", 310)
            assertFalse(File(shots, "06-globe.png").readBytes().contentEquals(File(shots, "06-globe-dragged.png").readBytes()))
            scene.tap(195, 774, 350)
        }
        assertEquals(1, entered)
    }

    private fun ImageComposeScene.tap(x: Int, y: Int, at: Long) {
        val pointer = Offset(x * 2f, y * 2f)
        sendPointerEvent(PointerEventType.Press, pointer, type = PointerType.Touch, timeMillis = at)
        render((at + 12) * 1_000_000)
        sendPointerEvent(PointerEventType.Release, pointer, type = PointerType.Touch, timeMillis = at + 48)
        render((at + 70) * 1_000_000)
    }

    private fun ImageComposeScene.shot(name: String, at: Long) {
        val image = render(at * 1_000_000)
        File(shots, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
}
