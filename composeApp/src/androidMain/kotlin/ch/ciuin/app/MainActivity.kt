package ch.ciuin.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import ch.ciuin.app.listener.ListeningNotification
import ch.ciuin.app.listener.LiveListeningService
import ch.ciuin.app.measure.AndroidMeasurementCapture
import ch.ciuin.app.route.QuietPlaceRoute
import ch.ciuin.app.route.quietPlaceRoute
import ch.ciuin.app.route.saveQuietPlaceRoute

class MainActivity : ComponentActivity() {
    private val ciuin: CiuinApplication get() = application as CiuinApplication
    private var quietRoute by mutableStateOf<QuietPlaceRoute?>(null)
    private var quietPlaceRouteRequest by mutableIntStateOf(0)
    private var measurementForeground by mutableStateOf(false)
    private var measurementCancellationCount by mutableIntStateOf(0)
    private var permissionRequestInFlight = false
    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionRequestInFlight = false
        if (granted) {
            startListening()
        } else {
            ciuin.listenerMessage = if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
                NOTIFICATIONS_REQUIRED
            } else {
                "Allow microphone access to start listening."
            }
        }
    }
    private val requestLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRequestInFlight = false
        startListening(requestPreciseLocation = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = ciuin
        val measurementCapture = AndroidMeasurementCapture(this, app)
        quietRoute = if (savedInstanceState == null) intent.quietPlaceRoute() else savedInstanceState.quietPlaceRoute()
        setContent {
            App(
                personalModel = app.personalModel,
                api = app.placesApi,
                liveListening = app.isListening,
                onLiveListeningChange = ::setLiveListening,
                liveListenerMessage = app.listenerMessage,
                quietPlaceRoute = quietRoute,
                onClearQuietPlaceRoute = { quietRoute = null },
                quietPlaceRouteRequest = quietPlaceRouteRequest,
                walkingRouter = if (quietRoute != null) app.walkingRouter else null,
                measurementCapture = measurementCapture,
                measurementForeground = measurementForeground,
                measurementCancellationCount = measurementCancellationCount,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        measurementForeground = true
    }

    override fun onStop() {
        measurementForeground = false
        measurementCancellationCount++
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        quietRoute = intent.quietPlaceRoute()
        if (quietRoute != null) quietPlaceRouteRequest++
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.saveQuietPlaceRoute(quietRoute)
    }

    private fun setLiveListening(listening: Boolean) {
        if (listening) startListening() else LiveListeningService.stop(this)
    }

    private fun startListening(requestPreciseLocation: Boolean = true) {
        if (ciuin.isListening || permissionRequestInFlight || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return

        val missingPermission = when {
            !hasPermission(Manifest.permission.RECORD_AUDIO) -> Manifest.permission.RECORD_AUDIO
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.POST_NOTIFICATIONS) ->
                Manifest.permission.POST_NOTIFICATIONS
            else -> null
        }
        if (missingPermission != null) {
            permissionRequestInFlight = true
            requestPermission.launch(missingPermission)
            return
        }
        if (!ListeningNotification.canShow(this)) {
            ciuin.listenerMessage = NOTIFICATIONS_REQUIRED
            return
        }
        if (requestPreciseLocation && !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            permissionRequestInFlight = true
            requestLocation.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            return
        }

        ciuin.listenerMessage = null
        try {
            LiveListeningService.start(this)
        } catch (error: RuntimeException) {
            Log.w("MainActivity", "Could not start live listening", error)
            ciuin.listenerMessage = "Couldn't start listening. Try again from the map."
        }
    }

    private fun hasPermission(permission: String): Boolean = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val NOTIFICATIONS_REQUIRED = "Enable listening notifications in Android settings to start listening."
    }
}
