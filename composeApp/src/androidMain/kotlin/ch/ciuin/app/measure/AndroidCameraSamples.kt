package ch.ciuin.app.measure

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val TAG = "AndroidMeasurement"
private const val MIN_AE_WARMUP_NS = 500_000_000L
private const val MAX_AE_WARMUP_NS = 1_500_000_000L

internal fun cameraSamples(context: Context, displayRotation: Int): Flow<CameraSample> = callbackFlow {
    // Light only (brightness + flicker). No person detection: frames are reduced to one luminance value.
    val camera = CameraStream(
        context = context,
        displayRotation = displayRotation,
        onFrame = { image, steadyExposure, _ ->
            if (steadyExposure) trySend(CameraSample.Luminance(image.timestamp, image.meanLuminance()))
        },
        onFailure = { cause ->
            Log.w(TAG, "Camera capture unavailable", cause)
            close(cause)
        },
    )
    try {
        camera.start()
        awaitClose()
    } finally {
        withContext(NonCancellable) { camera.stop() }
    }
}.buffer(128)

private class CameraStream(
    private val context: Context,
    private val displayRotation: Int,
    private val onFrame: (image: Image, steadyExposure: Boolean, rotation: Int) -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private val thread = HandlerThread("ciuin-camera").apply { start() }
    private val handler = Handler(thread.looper)

    // Camera2 state and ImageReader callbacks use this handler.
    private var opening = false
    private var stopped = false
    private var reader: ImageReader? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    @SuppressLint("MissingPermission")
    fun start() {
        handler.post {
            try {
                if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    throw SecurityException("Camera permission is required")
                }
                val manager = checkNotNull(context.getSystemService(CameraManager::class.java))
                val config = selectCamera(manager, displayRotation) ?: error("No compatible camera")
                Log.d(TAG, "Camera ${config.size}, target fps ${config.range}, AE lock ${config.canLockExposure}")
                manager.openCamera(config.id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        opening = false
                        if (stopped) {
                            camera.close()
                            thread.quitSafely()
                            return
                        }
                        device = camera
                        try {
                            configure(camera, config)
                        } catch (error: Exception) {
                            onFailure(error)
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        closeDevice(camera)
                        if (!stopped) onFailure(IllegalStateException("Camera disconnected"))
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        closeDevice(camera)
                        if (!stopped) onFailure(IllegalStateException("Camera2 error $error"))
                    }
                }, handler)
                opening = true
            } catch (error: Exception) {
                onFailure(error)
            }
        }
    }

    suspend fun stop() {
        val released = CompletableDeferred<Unit>()
        handler.post {
            stopped = true
            try {
                session?.close()
                device?.close()
                reader?.close()
            } finally {
                if (!opening) thread.quitSafely()
                released.complete(Unit)
            }
        }
        released.await()
    }

    private fun closeDevice(camera: CameraDevice) {
        opening = false
        camera.close()
        if (stopped) thread.quitSafely()
    }

    @Suppress("DEPRECATION")
    private fun configure(camera: CameraDevice, config: CameraConfig) {
        val images = ImageReader.newInstance(config.size.width, config.size.height, ImageFormat.YUV_420_888, 4)
        reader = images
        val exposure = ExposureLock(config)
        images.setOnImageAvailableListener({ source ->
            try {
                source.acquireLatestImage()?.use { image ->
                    if (!stopped) onFrame(image, exposure.readyFor(image.timestamp), config.rotation)
                }
            } catch (error: Exception) {
                if (!stopped) onFailure(error)
            }
        }, handler)

        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(images.surface)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            config.range?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            if (config.canMeasureWithoutAntibanding) {
                set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF)
            }
        }
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                if (stopped) {
                    captureSession.close()
                    return
                }
                session = captureSession
                try {
                    captureSession.setRepeatingRequest(request.build(), exposure.callback(request), handler)
                } catch (error: Exception) {
                    onFailure(error)
                }
            }

            override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                if (!stopped) onFailure(IllegalStateException("Could not configure camera capture"))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val configuration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(images.surface)),
                { command -> handler.post(command) },
                callback,
            )
            if (config.range != null) configuration.setSessionParameters(request.build())
            camera.createCaptureSession(configuration)
        } else {
            camera.createCaptureSession(listOf(images.surface), callback, handler)
        }
    }

    private inner class ExposureLock(private val config: CameraConfig) {
        private var firstTimestamp: Long? = null
        private var lockedTimestamp: Long? = null
        private var requested = false

        fun readyFor(timestamp: Long): Boolean {
            val ready = if (config.canLockExposure) lockedTimestamp else firstTimestamp?.plus(MAX_AE_WARMUP_NS)
            return ready != null && timestamp >= ready
        }

        fun callback(builder: CaptureRequest.Builder) = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                captureSession: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                if (stopped) return
                val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                val first = firstTimestamp ?: timestamp.also { firstTimestamp = it }
                if (!config.canLockExposure || lockedTimestamp != null) return
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                if (request.get(CaptureRequest.CONTROL_AE_LOCK) == true &&
                    (result.get(CaptureResult.CONTROL_AE_LOCK) == true || aeState == CaptureResult.CONTROL_AE_STATE_LOCKED)
                ) {
                    lockedTimestamp = timestamp
                    return
                }
                val settled = aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                val elapsed = timestamp - first
                if (!requested && (elapsed >= MAX_AE_WARMUP_NS || settled && elapsed >= MIN_AE_WARMUP_NS)) {
                    requested = true
                    try {
                        builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                        captureSession.setRepeatingRequest(builder.build(), this, handler)
                    } catch (error: Exception) {
                        Log.w(TAG, "Could not lock exposure for flicker measurement", error)
                    }
                }
            }
        }
    }
}

private data class CameraConfig(
    val id: String,
    val size: Size,
    val range: Range<Int>?,
    val rotation: Int,
    val canLockExposure: Boolean,
    val canMeasureWithoutAntibanding: Boolean,
)

private fun selectCamera(manager: CameraManager, displayRotation: Int): CameraConfig? {
    val cameras = manager.cameraIdList.map { id -> id to manager.getCameraCharacteristics(id) }
        .sortedBy { (_, metadata) -> metadata.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK }

    for ((id, metadata) in cameras) {
        val streams = metadata.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
        val sizes = streams.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        val practical = sizes.filter { minOf(it.width, it.height) >= 240 && it.width * it.height <= 1280 * 720 }
        val ranges = metadata.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val options = practical.ifEmpty { sizes.toList() }.map { size ->
            val duration = streams.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size)
            val range = ranges.filter { it.upper <= 60 && (duration == 0L || it.upper * duration <= 1_020_000_000L) }
                .maxWithOrNull(compareBy<Range<Int>>({ it.upper }, { it.lower }))
            size to range
        }
        val (size, range) = options.maxWithOrNull(compareBy(
            { it.second?.upper ?: 0 },
            { it.second?.lower ?: 0 },
            { -abs(it.first.width * it.first.height - 640 * 480) },
        )) ?: continue

        val front = metadata.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val sensorOrientation = metadata.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val clockwiseRotation = displayRotation + (if (front) -sensorOrientation else sensorOrientation)
        val rotation = (clockwiseRotation + 360) % 360
        return CameraConfig(
            id = id,
            size = size,
            range = range,
            rotation = rotation,
            canLockExposure = metadata.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true,
            canMeasureWithoutAntibanding = metadata.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)
                ?.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF) == true,
        )
    }
    return null
}

/** Mean of the Y (luma) plane, sampling every 8th pixel in both directions. */
internal fun Image.meanLuminance(): Double {
    val plane = planes[0]
    val data = plane.buffer
    val start = data.position()
    var sum = 0L
    var count = 0
    for (row in 0 until height step 8) {
        for (column in 0 until width step 8) {
            sum += data.get(start + row * plane.rowStride + column * plane.pixelStride).toInt() and 0xff
            count++
        }
    }
    return sum.toDouble() / count
}
