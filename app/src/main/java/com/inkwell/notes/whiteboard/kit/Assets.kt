package com.inkwell.notes.whiteboard.kit

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import android.util.Size
import androidx.core.content.FileProvider
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.storage.FileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** An image stored in a document's assets folder. */
data class StoredImage(val assetId: String, val width: Int, val height: Int)

/** Importing, decoding and copying document image assets. All functions do their I/O off the main thread. */
object DocAssets {
    private const val TAG = "DocAssets"
    /** Longest side of imported images; keeps memory and file sizes sane for 200MP photos. */
    const val MAX_IMPORT_SIDE = 3200

    /**
     * Copies a picked image into the document's assets: decoded with EXIF orientation applied,
     * downscaled to [MAX_IMPORT_SIDE], stored as JPEG (opaque) or PNG (with transparency).
     */
    suspend fun importImage(context: Context, graph: AppGraph, docId: String, uri: Uri): StoredImage = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = try {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = info.size
                val longest = max(s.width, s.height)
                if (longest > MAX_IMPORT_SIDE) {
                    val f = MAX_IMPORT_SIDE.toFloat() / longest
                    decoder.setTargetSize((s.width * f).roundToInt().coerceAtLeast(1), (s.height * f).roundToInt().coerceAtLeast(1))
                }
            }
        } catch (e: IOException) {
            throw IOException("This image could not be read.", e)
        } catch (e: ImageDecoder.DecodeException) {
            throw IOException("This image format is not supported.", e)
        }
        try {
            saveBitmap(graph, docId, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** Stores [bitmap] as a new asset (PNG when it has transparency or [forcePng], else JPEG). */
    suspend fun saveBitmap(graph: AppGraph, docId: String, bitmap: Bitmap, forcePng: Boolean = false): StoredImage = withContext(Dispatchers.IO) {
        val png = forcePng || bitmap.hasAlpha()
        val assetId = UUID.randomUUID().toString() + if (png) ".png" else ".jpg"
        val file = graph.files.assetFile(docId, assetId)
        FileStore.writeAtomically(file, keepBackup = false) { out ->
            val ok = bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 92, out)
            if (!ok) throw IOException("Could not encode the image.")
        }
        StoredImage(assetId, bitmap.width, bitmap.height)
    }

    /** Decodes an asset, subsampled so its longest side is at most about [maxSide] pixels. */
    fun decodeAsset(graph: AppGraph, docId: String, assetId: String, maxSide: Int = 4096): Bitmap? {
        val file = graph.files.assetFile(docId, assetId)
        if (!file.isFile) return null
        return decodeSampled(file, maxSide)
    }

    fun decodeSampled(file: File, maxSide: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else {
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Could not decode asset ${file.name}: ${t.javaClass.simpleName}")
        null
    }

    /** Pixel size of an image file without decoding it. */
    fun imageSize(file: File): Size? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return if (o.outWidth > 0 && o.outHeight > 0) Size(o.outWidth, o.outHeight) else null
    }

    /** Copies the given assets from one document to another (skipping ones already there). */
    suspend fun copyAssets(graph: AppGraph, fromDocId: String, toDocId: String, assetIds: Collection<String>) = withContext(Dispatchers.IO) {
        if (fromDocId == toDocId) return@withContext
        for (id in assetIds) {
            val src = graph.files.assetFile(fromDocId, id)
            val dst = graph.files.assetFile(toDocId, id)
            if (!src.isFile || dst.exists()) continue
            FileStore.writeAtomically(dst, keepBackup = false) { out -> src.inputStream().use { it.copyTo(out) } }
        }
    }
}

/** Sharing and saving generated files. */
object FileSharing {
    fun authority(context: Context): String = context.packageName + ".files"

    /** Opens the system share sheet for [file]. Returns false when nothing can receive it. */
    fun share(context: Context, file: File, mime: String, chooserTitle: String): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(context, authority(context), file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: IllegalArgumentException) {
            Log.e("FileSharing", "File is outside the shared paths", e)
            false
        }
    }

    /** Copies [file] to a user-chosen document [target] (from ACTION_CREATE_DOCUMENT). */
    suspend fun copyTo(context: Context, file: File, target: Uri) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(target, "wt") ?: throw IOException("The destination could not be opened.")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    /** A file name safe for every storage provider. */
    fun safeName(name: String, fallback: String = "Inkwell"): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifBlank { fallback }
}
