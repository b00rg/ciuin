package ch.ciuin.app.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsControllerTest {
    @Test
    fun roundTripsSettingsAndKeepsExplicitMotionOverride() {
        val storage = MemorySettingsStorage()
        val settings = SettingsController(storage, systemReduceMotion = true)
        assertTrue(settings.reduceMotion)
        settings.onboardingComplete = true
        settings.listenInBackground = true
        settings.gentleAlerts = false
        settings.reduceMotion = false
        settings.rememberPlace("p1", "Library")
        settings.recordSharedMeasurement()
        settings.selectModel(ModelVariant.M)

        val reloaded = SettingsController(storage, systemReduceMotion = true)
        assertTrue(reloaded.onboardingComplete)
        assertTrue(reloaded.listenInBackground)
        assertFalse(reloaded.gentleAlerts)
        assertFalse(reloaded.reduceMotion)
        assertEquals(listOf(RecentPlace("p1", "Library")), reloaded.recentPlaces)
        assertEquals(1, reloaded.sharedMeasurements)
        assertEquals(ModelVariant.M, reloaded.modelVariant)
    }

    @Test
    fun keepsSharedMeasurementsNewestFirstUpToTheLimit() {
        val storage = MemorySettingsStorage()
        val settings = SettingsController(storage)
        fun record(i: Int) = MeasurementRecord("osm:node/$i", "Place $i", 46.5, 6.6, "2026-11-22T10:00:${i % 60}Z", dbMean = i.toDouble())
        settings.recordSharedMeasurement(record(1))
        settings.recordSharedMeasurement(record(2))
        assertEquals(listOf("osm:node/2", "osm:node/1"), settings.measurementHistory.map { it.placeId })
        assertEquals(settings.measurementHistory, SettingsController(storage).measurementHistory)

        repeat(MEASUREMENT_HISTORY_LIMIT) { settings.recordSharedMeasurement(record(it + 3)) }
        assertEquals(MEASUREMENT_HISTORY_LIMIT, settings.measurementHistory.size)
        assertEquals("osm:node/${MEASUREMENT_HISTORY_LIMIT + 2}", settings.measurementHistory.first().placeId)
        assertEquals(MEASUREMENT_HISTORY_LIMIT + 2, settings.sharedMeasurements)
    }

    @Test
    fun remembersTheAppearanceAndDefaultsToLight() {
        val storage = MemorySettingsStorage()
        val settings = SettingsController(storage)
        assertEquals(Appearance.Light, settings.appearance)
        settings.appearance = Appearance.Dark
        assertEquals(Appearance.Dark, SettingsController(storage).appearance)
        // "System" was saved by an earlier build that had a Match phone option.
        assertEquals(Appearance.Light, SettingsController(MemorySettingsStorage("""{"appearance":"System"}""")).appearance)
    }

    @Test
    fun readsSettingsSavedBeforeTheHistoryExisted() {
        val settings = SettingsController(MemorySettingsStorage("""{"sharedMeasurements":3}"""))
        assertEquals(3, settings.sharedMeasurements)
        assertEquals(emptyList(), settings.measurementHistory)
    }

    @Test
    fun restoresDefaultsFromCorruptDataAndFollowsSystemMotion() {
        val settings = SettingsController(MemorySettingsStorage("{bad"), systemReduceMotion = false)
        assertEquals(AppSettings(), settings.state)
        assertFalse(settings.reduceMotion)
        settings.refreshSystemReduceMotion(true)
        assertTrue(settings.reduceMotion)
    }

    @Test
    fun mergesEditsMadeWhileTheSavedSettingsLoad() = runTest {
        val storage = MemorySettingsStorage("""{"sharedMeasurements":3,"listenInBackground":true,"gentleAlerts":false}""")
        val settings = SettingsController(storage, scope = backgroundScope)
        settings.listenInBackground = false
        settings.recordSharedMeasurement()
        settings.awaitLoaded()
        assertFalse(settings.loading)
        assertFalse(settings.listenInBackground)
        assertFalse(settings.gentleAlerts)
        assertEquals(4, settings.sharedMeasurements)
        assertEquals(4, SettingsController(storage).sharedMeasurements)
    }

    @Test
    fun readsFutureSettingsAndCapsRecentsWithMostRecentFirst() {
        val storage = MemorySettingsStorage("""{"onboardingComplete":true,"newSetting":1,"modelVariant":"XL"}""")
        val settings = SettingsController(storage)
        for (index in 1..6) settings.rememberPlace("p$index", "Place $index")
        settings.rememberPlace("p3", "Renamed place")

        val reloaded = SettingsController(storage)
        assertTrue(reloaded.onboardingComplete)
        assertEquals(ModelVariant.S, reloaded.modelVariant)
        assertEquals(listOf("p3", "p6", "p5", "p4", "p2"), reloaded.recentPlaces.map { it.placeId })
        assertEquals("Renamed place", reloaded.recentPlaces.first().name)
    }
}
