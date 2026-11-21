package ch.ciuin.app

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import ch.ciuin.app.personal.DesktopPersonalModelStorage
import ch.ciuin.app.personal.PersonalModelController

fun main() = application {
    val personalModelScope = rememberCoroutineScope()
    val personalModel = remember { PersonalModelController(DesktopPersonalModelStorage(), personalModelScope) }
    Window(onCloseRequest = ::exitApplication, title = "Ciúin – Sensory Map") {
        App(personalModel = personalModel)
    }
}
