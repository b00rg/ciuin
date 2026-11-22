package ch.ciuin.app.ui

import ch.ciuin.app.ui.gallery.FoundationsGallery
import ch.ciuin.app.ui.gallery.GallerySection
import ch.ciuin.app.ui.gallery.GallerySections
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the Foundations gallery, one PNG per section, into `composeApp/build/design-shots/gallery/`.
 * Compare them with `00-foundations.png`, `00-motion.png` and `00-icons.png` from the Paper export.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "ch.ciuin.app.ui.*"
 * ```
 */
class FoundationsGalleryTest {
    @Test
    fun rendersEverySection() {
        GallerySections.forEachIndexed { index, section ->
            val name = "gallery/%02d-%s".format(index + 1, section.title.lowercase().replace(Regex("[^a-z]+"), "-"))
            val file = DesignShots.fitted(name) { GallerySection(section.title) { section.content() } }
            assertTrue(file.length() > 2_000, "${file.name} is empty")
        }
    }

    /**
     * The gallery as it opens on a phone, for comparing with a screenshot from a device: at the
     * artboard's size, and at the size and density of the iPhone 17 Pro simulator. A device draws the
     * same picture below its status bar. (For Android, `adb shell wm size 780x1688` with
     * `wm density 320` puts an emulator at exactly 390 x 844 dp, density 2.)
     */
    @Test
    fun rendersAsAScreen() {
        val artboard = DesignShots.screen("gallery/00-screen") { FoundationsGallery() }
        val iphone = DesignShots.screen("gallery/00-screen-402x874@3x", width = 402, height = 874, density = 3f) {
            FoundationsGallery()
        }
        assertTrue(artboard.length() > 2_000 && iphone.length() > 2_000)
    }

    @Test
    fun rendersWithReduceMotion() {
        val characters = GallerySections.first { it.title == "Characters" }
        val file = DesignShots.fitted("gallery/04-characters-reduce-motion", reduceMotion = true) {
            GallerySection(characters.title) { characters.content() }
        }
        assertTrue(file.length() > 2_000)
    }
}
