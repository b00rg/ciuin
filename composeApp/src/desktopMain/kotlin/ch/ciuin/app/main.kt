package ch.ciuin.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import ch.ciuin.app.core.AlertCenter
import ch.ciuin.app.core.AppEnvironment
import ch.ciuin.app.core.DesktopLocationSource
import ch.ciuin.app.core.DesktopPermissions
import ch.ciuin.app.core.DesktopSettingsStorage
import ch.ciuin.app.core.ModelCatalog
import ch.ciuin.app.core.RouteController
import ch.ciuin.app.core.SettingsController
import ch.ciuin.app.personal.DesktopPersonalModelStorage
import ch.ciuin.app.personal.PersonalModelController
import ch.ciuin.app.route.WalkingRouter

fun main() = application {
    val scope = rememberCoroutineScope()
    val environment = remember {
        val api = createPlacesApi(BundledPlacesApi(::readBundledPlaces))
        val personal = PersonalModelController(DesktopPersonalModelStorage(), scope)
        val settings = SettingsController(DesktopSettingsStorage(), scope = scope)
        val router = WalkingRouter()
        val routes = RouteController(scope, router)
        AppEnvironment(
            api, personal, router, settings, DesktopPermissions, DesktopLocationSource,
            listening = null,
            alerts = AlertCenter(settings, routes, scope, recordOkay = { alert ->
                personal.recordAlertFeedback(alert.window, label = 0)
            }),
            routes = routes,
            measurement = null,
            models = ModelCatalog(settings, emptySet()).apply { load(scope) },
        )
    }
    DisposableEffect(environment) {
        onDispose { environment.walkingRouter?.close() }
    }
    Window(onCloseRequest = ::exitApplication, title = "Ciúin – Sensory Map") {
        App(environment)
    }
}
