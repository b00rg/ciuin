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
 * Roboto, as Android renders it: static 400 and 500 instances of Android's own variable Roboto,
 * bundled by the Xcode project (iosApp/iosApp/Fonts). Without this Compose falls back to San Francisco.
 */
internal actual val appFontFamily: FontFamily? by lazy {
    FontFamily(
        Font("Roboto-Regular", bundledFont("Roboto-Regular"), FontWeight.Normal),
        Font("Roboto-Medium", bundledFont("Roboto-Medium"), FontWeight.Medium),
    )
}

/** The one glyph the app uses that Roboto lacks, cut from the Noto Sans Symbols that Android falls back to. */
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
