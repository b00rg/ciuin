package ch.ciuin.shared.camera

/**
 * Camera / light-sensor features. Frames are processed and discarded immediately;
 * only these numbers leave the module.
 */
interface CameraFeatures {
    /** Mean brightness in lux (ambient light sensor where available). */
    fun brightness(): Double

    /**
     * Flicker estimate from a luminance time series sampled at [fps].
     * @return dominant frequency (Hz, null if none detectable) and flicker index.
     */
    fun flicker(luminance: FloatArray, fps: Double): FlickerResult

    /** Number of people detected in a single frame. */
    fun peopleCount(frame: Frame): Int
}

data class FlickerResult(val dominantHz: Double?, val flickerIndex: Double)

/** Platform-neutral frame: tightly packed pixel bytes plus dimensions. */
class Frame(val width: Int, val height: Int, val pixels: ByteArray, val format: PixelFormat)

enum class PixelFormat { RGBA_8888, YUV_420_888, LUMA_8 }
