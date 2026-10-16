package com.inkwell.notes.ink.render

import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import com.inkwell.notes.ink.InkDocumentSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * LRU cache of decoded images (ImageItem assets). Thread safe: tiles render on a worker thread
 * and call [get]; misses trigger an asynchronous load through [InkDocumentSource.loadImage].
 */
internal class ImageCache(private val scope: CoroutineScope, maxBytes: Int) {
    @Volatile var source: InkDocumentSource? = null
    /** Called on the main thread when an image became available. */
    var onLoaded: ((String) -> Unit)? = null

    private val lru = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** The cached bitmap, or null (and a background load starts). */
    fun get(assetId: String): Bitmap? {
        val b = lru.get(assetId)
        if (b != null && !b.isRecycled) return b
        request(assetId)
        return null
    }

    fun peek(assetId: String): Bitmap? = lru.get(assetId)?.takeUnless { it.isRecycled }

    fun request(assetId: String) {
        val src = source ?: return
        val failed = failedAt[assetId]
        if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return
        if (!inFlight.add(assetId)) return
        scope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { src.loadImage(assetId)?.let { Bitmaps.prepare(it, MAX_IMAGE_SIDE) } }
                if (bmp != null && source === src) {
                    lru.put(assetId, bmp)
                    failedAt.remove(assetId)
                    onLoaded?.invoke(assetId)
                } else if (bmp == null) {
                    failedAt[assetId] = System.currentTimeMillis()
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TAG, "Image could not be loaded (${t.javaClass.simpleName})")
                failedAt[assetId] = System.currentTimeMillis()
            } finally {
                inFlight.remove(assetId)
            }
        }
    }

    fun clear() {
        lru.evictAll()
        inFlight.clear()
        failedAt.clear()
    }

    fun trimToHalf() { lru.trimToSize(lru.maxSize() / 2) }

    companion object {
        private const val TAG = "InkImages"
        private const val RETRY_MS = 30_000L
        /** Images are kept at most this large; page images rarely need more on a tablet. */
        private const val MAX_IMAGE_SIDE = 2560
    }
}
