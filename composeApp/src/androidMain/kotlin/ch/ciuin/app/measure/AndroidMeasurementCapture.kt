package ch.ciuin.app.measure

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ch.ciuin.app.CiuinApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AndroidMeasurementCapture(
    private val activity: ComponentActivity,
    private val app: CiuinApplication,
) : MeasurementCapture {
    override val device = "Android ${Build.MODEL}"

    private val sensors = activity.getSystemService(SensorManager::class.java)
    private val lightSensor = sensors?.getDefaultSensor(Sensor.TYPE_LIGHT)
    override val canMeasureLux = lightSensor != null
    private var screenUsers = 0
    private var permissionResult: CompletableDeferred<CapturePermissions>? = null
    private val permissions = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionResult?.complete(currentPermissions())
    }

    override suspend fun requestPermissions(): CapturePermissions {
        val current = currentPermissions()
        val missing = buildList {
            if (!current.camera && activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                add(Manifest.permission.CAMERA)
            }
            if (!current.microphone && activity.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
                add(Manifest.permission.RECORD_AUDIO)
            }
        }
        if (missing.isEmpty()) return current

        val result = CompletableDeferred<CapturePermissions>()
        permissionResult = result
        return try {
            permissions.launch(missing.toTypedArray())
            result.await()
        } finally {
            if (permissionResult === result) permissionResult = null
        }
    }

    override fun camera(): Flow<CameraSample> = whileForeground(
        cameraSamples(app, displayRotationDegrees()),
    )

    override fun lux(): Flow<Double> = whileForeground(callbackFlow {
        val manager = sensors
        val sensor = lightSensor
        if (manager == null || sensor == null) {
            close()
            return@callbackFlow
        }
        val latest = MutableStateFlow<Double?>(null)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val value = event.values[0].toDouble()
                if (value.isFinite() && value >= 0.0) latest.value = value
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, Handler(Looper.getMainLooper()))) {
            close()
            return@callbackFlow
        }
        // TYPE_LIGHT reports changes. Take equally spaced readings of the latest reported value.
        val sampler = launch {
            while (isActive) {
                latest.value?.let { trySend(it) }
                delay(1_000L)
            }
        }
        awaitClose {
            sampler.cancel()
            manager.unregisterListener(listener)
        }
    })

    override fun audio(): Flow<AudioSample> = whileForeground(app.audioWindows.map { it.getOrThrow() })

    private fun currentPermissions() = CapturePermissions(
        camera = activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        microphone = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
    )

    private fun displayRotationDegrees(): Int = when (activity.window.decorView.display?.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun <T> whileForeground(source: Flow<T>): Flow<T> = channelFlow {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) cancel()
        }
        var observing = false
        try {
            withContext(Dispatchers.Main.immediate) {
                if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    activity.lifecycle.addObserver(observer)
                    observing = true
                    if (screenUsers++ == 0) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            if (observing) source.collect { send(it) }
        } finally {
            if (observing) withContext(NonCancellable + Dispatchers.Main.immediate) {
                activity.lifecycle.removeObserver(observer)
                if (--screenUsers == 0) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}
