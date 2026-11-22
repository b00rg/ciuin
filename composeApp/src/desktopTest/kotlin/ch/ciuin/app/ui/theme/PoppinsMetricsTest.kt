package ch.ciuin.app.ui.theme

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the one thing that keeps text the same height on Android and iOS: the bundled Poppins is
 * 1.1 em tall, and no text style asks for a line shorter than that. Android 15 will not draw a line
 * shorter than its font, so a tighter style, or the stock Poppins files (1.4 em), would make text
 * taller on Android only.
 */
class PoppinsMetricsTest {
    private val weights = listOf("regular", "medium", "semibold", "bold")

    @Test
    fun bundledFontsAreOnePointOneEmTall() {
        for (weight in weights) {
            val font = Font.read("font/poppins_$weight.ttf")
            assertEquals(1000, font.unitsPerEm, weight)
            assertEquals(Triple(900, -200, 0), font.hhea, "$weight hhea ascender, descender, line gap")
            assertEquals(Triple(900, -200, 0), font.typo, "$weight OS/2 typo ascender, descender, line gap")
            assertTrue("DSIG" !in font.tables, "$weight still carries a signature that no longer matches")
        }
    }

    @Test
    fun glyphsSitWhereTheStockFontPutsThem() {
        // The stock metrics are 1050 and -350. Centred text depends only on their difference.
        for (weight in weights) {
            val (ascender, descender, _) = Font.read("font/poppins_$weight.ttf").hhea
            assertEquals(1050 - 350, ascender + descender, weight)
        }
    }

    @Test
    fun noTextStyleIsTighterThanTheFont() {
        for ((name, style) in CiuinType.all) {
            val ratio = style.lineHeight.value / style.fontSize.value
            assertTrue(ratio >= 1.1f, "$name is ${style.fontSize.value}/${style.lineHeight.value}: tighter than the font")
        }
    }

    private class Font(val tables: Map<String, ByteBuffer>) {
        val unitsPerEm: Int get() = tables.getValue("head").getShort(18).toInt() and 0xFFFF
        val hhea: Triple<Int, Int, Int> get() = tables.getValue("hhea").let { triple(it, 4) }
        val typo: Triple<Int, Int, Int> get() = tables.getValue("OS/2").let { triple(it, 68) }

        private fun triple(table: ByteBuffer, at: Int) =
            Triple(table.getShort(at).toInt(), table.getShort(at + 2).toInt(), table.getShort(at + 4).toInt())

        companion object {
            fun read(resource: String): Font {
                val stream = assertNotNull(Font::class.java.classLoader.getResourceAsStream(resource), "$resource is not on the classpath")
                val bytes = stream.use { it.readBytes() }
                val file = ByteBuffer.wrap(bytes)
                val count = file.getShort(4).toInt()
                val tables = (0 until count).associate { index ->
                    val entry = 12 + 16 * index
                    val tag = String(bytes, entry, 4, Charsets.US_ASCII)
                    tag to ByteBuffer.wrap(bytes, file.getInt(entry + 8), file.getInt(entry + 12)).slice()
                }
                return Font(tables)
            }
        }
    }
}
