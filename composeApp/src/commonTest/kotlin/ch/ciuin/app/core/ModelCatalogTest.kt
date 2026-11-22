package ch.ciuin.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelCatalogTest {
    @Test
    fun parsesStudentMetricsAndKeepsUnavailableModelsUnselectable() {
        val storage = MemorySettingsStorage()
        val settings = SettingsController(storage)
        val available = setOf(ModelVariant.S, ModelVariant.M)
        val variants = parseModelResults(modelResultsJson, available)
        assertEquals(listOf(ModelVariant.S, ModelVariant.M, ModelVariant.L), variants.map { it.variant })
        assertEquals(ModelInfo(ModelVariant.M, 1.93, 0.717, 259_021, null), variants[1])
        assertTrue(variants[0].available)
        assertEquals("Not available yet", variants[2].unavailableReason)
        assertFalse(parseModelResults(modelResultsJson, emptySet()).any { it.available })

        val catalog = ModelCatalog(settings, available, variants)
        catalog.select(ModelVariant.M)
        catalog.select(ModelVariant.L)
        assertEquals(ModelVariant.M, catalog.selected)
        assertEquals(ModelVariant.M, SettingsController(storage).modelVariant)
    }
}
