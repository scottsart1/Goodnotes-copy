package com.inkwell.notes.editor

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.inkwell.notes.core.storage.FileStore
import com.inkwell.notes.editor.logic.ImageMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Decodes pictures (respecting EXIF orientation) and stores them as document assets. */
object ImageImporter {
    data class Imported(val assetId: String, val width: Int, val height: Int)

    /** Copies the image at [uri] into the assets of [docId], downscaled to at most 4096 px. */
    suspend fun importUri(context: Context, files: FileStore, docId: String, uri: Uri): Imported = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = decode(source, ImageMath.MAX_STORED_PX)
        try {
            val png = bitmap.hasAlpha()
            val id = "img_${UUID.randomUUID()}.${if (png) "png" else "jpg"}"
            FileStore.writeAtomically(files.assetFile(docId, id), keepBackup = false) { out ->
                if (!bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, out)) {
                    throw IOException("Image could not be encoded")
                }
            }
            Imported(id, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    /** Decodes an asset file for display, at most [maxPx] on the longer side. Null if unreadable. */
    fun decodeFile(file: File, maxPx: Int = ImageMath.MAX_DECODE_PX): Bitmap? {
        if (!file.isFile) return null
        return try {
            decode(ImageDecoder.createSource(file), maxPx)
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun decode(source: ImageDecoder.Source, maxPx: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = ImageMath.fitWithin(info.size.width, info.size.height, maxPx)
            if (w != info.size.width || h != info.size.height) decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }

    /** A content Uri (FileProvider, authority "<package>.files") for a new camera capture. */
    fun newCameraUri(context: Context): Pair<Uri, File> {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, context.packageName + ".files", file) to file
    }

    /** The first image Uri on the system clipboard, if any. */
    fun clipboardImageUri(context: Context): Uri? {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = cm.primaryClip ?: return null
        val desc = clip.description
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            val type = context.contentResolver.getType(uri)
            if (type?.startsWith("image/") == true || desc.hasMimeType("image/*")) return uri
        }
        return null
    }

    /** True when the system clipboard holds an image or text (reads only the description). */
    fun clipboardHasPasteable(context: Context): Boolean {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return false
        if (!cm.hasPrimaryClip()) return false
        val desc = cm.primaryClipDescription ?: return false
        return desc.hasMimeType("image/*") || desc.hasMimeType("text/*")
    }

    /** Plain text on the system clipboard, if any. */
    fun clipboardText(context: Context): String? {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        if (!clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && !clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) {
            if (clip.getItemAt(0).text == null) return null
        }
        return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
    }

    fun copyTextToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    }

    /** True on Android versions that show their own "copied" confirmation. */
    val systemShowsCopyConfirmation: Boolean get() = Build.VERSION.SDK_INT >= 33
}
