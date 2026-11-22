package ch.ciuin.shared.text

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.decomposedStringWithCanonicalMapping

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun decomposeUnicode(text: String): String =
    NSString.create(string = text).decomposedStringWithCanonicalMapping
