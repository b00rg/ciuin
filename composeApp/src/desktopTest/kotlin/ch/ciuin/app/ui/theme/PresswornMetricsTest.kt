package ch.ciuin.app.ui.theme

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pressworn is held to the same rule as Poppins (see [PoppinsMetricsTest]): the bundled file is 1.1 em
 * tall, so its 40/44 display styles are the same height on Android 15 as on iOS and desktop. The
 * stock file from `assets/` is 860 / -280; the bundled one is trimmed to 840 / -260 around the same
 * middle, so glyphs sit where they did.
 */
class PresswornMetricsTest {
    private val resource = "font/pressworn_regular.ttf"

    @Test
    fun bundledFontIsOnePointOneEmTall() {
        val tables = read(resource)
        assertEquals(1000, tables.getValue("head").getShort(18).toInt() and 0xFFFF, "units per em")
        assertEquals(Triple(840, -260, 0), triple(tables.getValue("hhea"), 4), "hhea ascender, descender, line gap")
        assertEquals(Triple(840, -260, 0), triple(tables.getValue("OS/2"), 68), "OS/2 typo ascender, descender, line gap")
    }

    @Test
    fun glyphsSitWhereTheStockFontPutsThem() {
        val (ascender, descender, _) = triple(read(resource).getValue("hhea"), 4)
        assertEquals(860 - 280, ascender + descender)
    }

    @Test
    fun displayStylesUsePressworn() {
        for (style in listOf(CiuinType.Hero, CiuinType.Display, CiuinType.ScreenTitle, CiuinType.HeadlineTight)) {
            assertEquals(Pressworn, style.fontFamily)
        }
        assertEquals(Poppins, CiuinType.PlaceTitle.fontFamily, "place names have accents Pressworn lacks")
        assertEquals(Poppins, CiuinType.Wordmark.fontFamily, "Pressworn cannot spell ciúin")
    }

    private fun triple(table: ByteBuffer, at: Int) =
        Triple(table.getShort(at).toInt(), table.getShort(at + 2).toInt(), table.getShort(at + 4).toInt())

    private fun read(resource: String): Map<String, ByteBuffer> {
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream(resource), "$resource is not on the classpath")
        val bytes = stream.use { it.readBytes() }
        val file = ByteBuffer.wrap(bytes)
        val count = file.getShort(4).toInt()
        return (0 until count).associate { index ->
            val entry = 12 + 16 * index
            String(bytes, entry, 4, Charsets.US_ASCII) to ByteBuffer.wrap(bytes, file.getInt(entry + 8), file.getInt(entry + 12)).slice()
        }
    }
}
