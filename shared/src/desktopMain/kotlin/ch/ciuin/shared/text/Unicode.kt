package ch.ciuin.shared.text

import java.text.Normalizer

actual fun decomposeUnicode(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
