package ch.ciuin.app.map

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class TileKey(val z: Int, val x: Int, val y: Int)

/** Decode PNG/JPEG bytes into an [ImageBitmap] (platform-specific). */
expect fun decodeTileImage(bytes: ByteArray): ImageBitmap

/**
 * Fetches and caches raster map tiles.
 *
 * [get] is called from the draw phase: it returns the tile if cached and otherwise starts a download.
 * Tiles live in a snapshot map, so the canvas redraws automatically when one arrives.
 * All bookkeeping happens on [scope]'s (main) thread; only decoding runs on [Dispatchers.Default].
 *
 * OSM tile usage policy: identify the app via User-Agent, keep load light (we cap concurrent requests
 * and cache in memory). For the projector demo with heavy panning, consider a different tile provider.
 */
class TileCache(
    private val scope: CoroutineScope,
    private val urlTemplate: String = OSM_TILES,
    private val maxTiles: Int = 300,
) {
    private val client = HttpClient {
        install(UserAgent) { agent = "Ciuin-SensoryMap/0.1 (hackathon prototype)" }
        expectSuccess = true
    }
    private val tiles = mutableStateMapOf<TileKey, ImageBitmap>()
    private val lru = LinkedHashSet<TileKey>()
    private val inFlight = HashSet<TileKey>()
    private val limit = Semaphore(4)

    /** Cached tile, or null (and a download is started). */
    fun get(key: TileKey): ImageBitmap? {
        val img = tiles[key]
        if (img != null) {
            lru.remove(key); lru.add(key)
        } else {
            request(key)
        }
        return img
    }

    /** Cached tile without triggering a download (used for lower-zoom fallbacks). */
    fun peek(key: TileKey): ImageBitmap? = tiles[key]

    private fun request(key: TileKey) {
        if (!inFlight.add(key)) return
        scope.launch {
            val img = runCatching {
                limit.withPermit {
                    val bytes = client.get(url(key)).readRawBytes()
                    withContext(Dispatchers.Default) { decodeTileImage(bytes) }
                }
            }.getOrNull()
            if (img != null) {
                tiles[key] = img
                lru.add(key)
                while (lru.size > maxTiles) {
                    val oldest = lru.first()
                    lru.remove(oldest)
                    tiles.remove(oldest)
                }
                inFlight.remove(key)
            } else {
                delay(RETRY_MS) // back off before the next draw is allowed to retry
                inFlight.remove(key)
            }
        }
    }

    private fun url(k: TileKey) = urlTemplate
        .replace("{z}", k.z.toString())
        .replace("{x}", k.x.toString())
        .replace("{y}", k.y.toString())

    fun close() = client.close()

    companion object {
        const val OSM_TILES = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        private const val RETRY_MS = 5_000L
    }
}
