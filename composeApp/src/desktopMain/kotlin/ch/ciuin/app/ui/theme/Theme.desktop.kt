package ch.ciuin.app.ui.theme

import androidx.compose.runtime.Composable

/** The desktop build is a development target and has no such setting. */
@Composable
actual fun systemReduceMotion(): Boolean = false
