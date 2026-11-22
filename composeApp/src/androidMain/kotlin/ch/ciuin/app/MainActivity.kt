package ch.ciuin.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import ch.ciuin.app.core.AndroidListeningControl
import ch.ciuin.app.core.AndroidLocationSource
import ch.ciuin.app.core.AndroidPermissions
import ch.ciuin.app.core.AppEnvironment
import ch.ciuin.app.core.AppPermission
import ch.ciuin.app.core.activeAlert
import ch.ciuin.app.core.androidReduceMotion
import ch.ciuin.app.measure.AndroidMeasurementCapture
import ch.ciuin.app.measure.MeasurementController
import ch.ciuin.app.route.quietPlaceRoute
import ch.ciuin.app.route.saveQuietPlaceRoute
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var environment: AppEnvironment
    private lateinit var location: AndroidLocationSource
    private val ciuin: CiuinApplication get() = application as CiuinApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = ciuin
        val permissions = AndroidPermissions(this)
        location = AndroidLocationSource(this)
        environment = AppEnvironment(
            app.placesApi, app.personalModel, app.walkingRouter, app.settings, permissions, location,
            AndroidListeningControl(this, app, permissions, lifecycleScope),
            app.alerts, app.routes,
            MeasurementController(
                AndroidMeasurementCapture(this, app, permissions), app.placesApi, app.settings, location, lifecycleScope,
            ).apply { setForeground(false) },
            app.models,
        )
        lifecycleScope.launch {
            snapshotFlow { permissions.status(AppPermission.LOCATION) }.collect { location.refresh() }
        }
        if (savedInstanceState == null) {
            openAlert(intent)
        } else if (app.routes.active == null) {
            savedInstanceState.quietPlaceRoute()?.let(app.routes::start)
        }
        setContent { App(environment) }
    }

    override fun onStart() {
        super.onStart()
        environment.measurement?.setForeground(true)
        location.setForeground(true)
    }

    override fun onResume() {
        super.onResume()
        environment.permissions.refresh()
        environment.settings.refreshSystemReduceMotion(androidReduceMotion(this))
        ciuin.listeningStats.refresh()
    }

    override fun onStop() {
        environment.measurement?.setForeground(false)
        location.setForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openAlert(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.saveQuietPlaceRoute(environment.routes.active?.takeIf { it == intent.quietPlaceRoute() })
    }

    private fun openAlert(intent: Intent) = lifecycleScope.launch {
        val alert = intent.activeAlert()
        val route = intent.quietPlaceRoute()
        if (alert == null && route == null) return@launch
        environment.settings.awaitLoaded()
        if (!environment.settings.gentleAlerts) return@launch
        if (alert != null && environment.alerts.active?.id != alert.id) environment.alerts.show(alert)
        route?.let(environment.routes::start)
    }
}
