package ch.ciuin.app

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/**
 * Poppins from the app bundle. The Xcode project copies the shared `src/commonMain/fontRes` directory
 * into the bundle (`iosApp/project.yml`); without it Compose would fall back to San Francisco.
 */
internal actual val appFontFamily: FontFamily by lazy {
    FontFamily(
        Font("Poppins-Regular", bundledFont("poppins_regular"), FontWeight.Normal),
        Font("Poppins-Medium", bundledFont("poppins_medium"), FontWeight.Medium),
        Font("Poppins-SemiBold", bundledFont("poppins_semibold"), FontWeight.SemiBold),
        Font("Poppins-Bold", bundledFont("poppins_bold"), FontWeight.Bold),
    )
}

internal actual val displayFontFamily: FontFamily by lazy {
    FontFamily(Font("Pressworn-Regular", bundledFont("pressworn_regular"), FontWeight.Normal))
}

/** The one glyph the app uses that Poppins lacks, cut from the Noto Sans Symbols that Android falls back to. */
internal actual val symbolFontFamily: FontFamily? by lazy {
    FontFamily(Font("NotoSansSymbols-House", bundledFont("NotoSansSymbols-House")))
}

@OptIn(ExperimentalForeignApi::class)
private fun bundledFont(name: String): ByteArray {
    val path = NSBundle.mainBundle.pathForResource(name, "ttf") ?: error("Font $name.ttf is not in the app bundle")
    val data = NSData.dataWithContentsOfFile(path) ?: error("Cannot read $path")
    return ByteArray(data.length.toInt()).apply {
        usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    }
}
