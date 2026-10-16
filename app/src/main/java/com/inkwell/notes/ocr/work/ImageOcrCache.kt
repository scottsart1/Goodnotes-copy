package com.inkwell.notes.ocr.work

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.inkwell.notes.core.storage.FileStore
import com.inkwell.notes.ocr.text.OcrScaling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** OCR of one image, boxes normalised to 0..1 of the image. */
@Serializable
internal data class CachedImageOcr(val engine: String, val blocks: List<CachedBlock>) {
    @Serializable
    data class CachedBlock(val text: String, val box: List<Float>, val confidence: Float? = null)
}

/**
 * Recognised text of embedded images, keyed by document, asset id and file size, so an image
 * placed on many pages (or re-indexed after an unrelated edit) is read only once.
 */
internal class ImageOcrCache(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(docId: String, assetId: String, size: Long) =
        File(dir, "${FileStore.safe(docId)}__${FileStore.safe(assetId)}__$size.json")

    suspend fun get(docId: String, assetId: String, size: Long): CachedImageOcr? = withContext(Dispatchers.IO) {
        val f = file(docId, assetId, size)
        if (!f.exists()) return@withContext null
        try {
            json.decodeFromString(CachedImageOcr.serializer(), f.readText())
        } catch (e: Exception) {
            f.delete()
            null
        }
    }

    suspend fun put(docId: String, assetId: String, size: Long, value: CachedImageOcr) = withContext(Dispatchers.IO) {
        try {
            dir.mkdirs()
            val bytes = json.encodeToString(CachedImageOcr.serializer(), value).encodeToByteArray()
            FileStore.writeAtomically(file(docId, assetId, size), keepBackup = false) { it.write(bytes) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not cache image OCR: ${e.javaClass.simpleName}")
        }
    }

    /** Forgets a document's cached results (when it is deleted). */
    suspend fun removeDocument(docId: String) = withContext(Dispatchers.IO) {
        val prefix = "${FileStore.safe(docId)}__"
        dir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
        Unit
    }

    companion object {
        private const val TAG = "ImageOcrCache"
        /** Images smaller than this (px, longest edge) carry no readable text. */
        const val MIN_EDGE = 48

        /** Decodes [file] subsampled for OCR; null when unreadable, tiny or out of memory. */
        fun decodeForOcr(file: File): Bitmap? = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || maxOf(bounds.outWidth, bounds.outHeight) < MIN_EDGE) {
                null
            } else {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = OcrScaling.sampleSize(bounds.outWidth, bounds.outHeight)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeFile(file.path, opts)
            }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Image too large to read")
            null
        } catch (e: Exception) {
            null
        }
    }
}
