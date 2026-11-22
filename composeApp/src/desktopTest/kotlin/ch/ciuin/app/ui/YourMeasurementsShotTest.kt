package ch.ciuin.app.ui

import androidx.compose.foundation.layout.WindowInsets
import ch.ciuin.app.core.MeasurementRecord
import ch.ciuin.app.ui.you.YourMeasurementsScreen
import kotlin.test.Test
import kotlin.test.assertTrue

/** "Your measurements" on the You tab, with readings and empty: `build/design-shots/you-measurements*.png`. */
class YourMeasurementsShotTest {
    @Test
    fun rendersTheListAndTheEmptyState() {
        val history = listOf(
            MeasurementRecord("osm:node/1", "Café du Grütli", 46.5236, 6.6327, "2026-11-22T13:20:00Z", 57.4, 82.0, 3),
            MeasurementRecord(null, null, 46.519, 6.562, "2026-11-21T08:05:00Z", 48.0, 310.0, 12),
            MeasurementRecord("osm:node/2", "Bibliothèque de l'EPFL", 46.5184, 6.5684, "2026-10-03T16:30:00Z", 41.0, null, 25),
        )
        val list = DesignShots.screen("you-measurements") {
            YourMeasurementsScreen(history, emptyList(), WindowInsets(0, 0, 0, 0), {}, {}, { _, _ -> })
        }
        val empty = DesignShots.screen("you-measurements-empty") {
            YourMeasurementsScreen(emptyList(), emptyList(), WindowInsets(0, 0, 0, 0), {}, {}, { _, _ -> })
        }
        assertTrue(list.length() > 2_000 && empty.length() > 2_000)
    }
}
