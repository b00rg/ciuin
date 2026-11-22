package ch.ciuin.shared.text

/** Decompose accents so search can remove combining marks on every target. */
expect fun decomposeUnicode(text: String): String
