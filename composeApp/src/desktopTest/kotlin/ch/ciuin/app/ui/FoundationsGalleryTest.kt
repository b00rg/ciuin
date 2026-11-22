package ch.ciuin.app.ui

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

    @Test
    fun rendersWithReduceMotion() {
        val characters = GallerySections.first { it.title == "Characters" }
        val file = DesignShots.fitted("gallery/04-characters-reduce-motion", reduceMotion = true) {
            GallerySection(characters.title) { characters.content() }
        }
        assertTrue(file.length() > 2_000)
    }
}
