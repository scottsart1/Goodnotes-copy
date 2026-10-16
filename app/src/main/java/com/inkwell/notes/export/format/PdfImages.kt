package com.inkwell.notes.export.format

import android.graphics.Bitmap
import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import java.io.FileInputStream

/**
 * Image XObjects for a PDF export, one per asset. Photos stay JPEG (the original bytes when
 * they are not far larger than needed), PNG and images with transparency are embedded
 * losslessly. Images larger than [maxSide] pixels are downsampled.
 */
internal class PdfImages(
    private val doc: PDDocument,
    private val assetFile: (docId: String, assetId: String) -> File,
    private val jpegQuality: Int,
    private val maxSide: Int,
) {
    private val cache = HashMap<String, PDImageXObject?>()

    fun get(docId: String, assetId: String): PDImageXObject? = cache.getOrPut("$docId/$assetId") { load(assetFile(docId, assetId)) }

    /** Embeds an already rendered bitmap (e.g. a rasterised PDF page) as JPEG or lossless. */
    fun fromBitmap(bitmap: Bitmap, lossless: Boolean): PDImageXObject =
        if (lossless) LosslessFactory.createFromImage(doc, bitmap)
        else JPEGFactory.createFromImage(doc, bitmap, jpegQuality.coerceIn(30, 100) / 100f)

    private fun load(file: File): PDImageXObject? {
        if (!file.isFile) return null
        return try {
            val (w, h) = ImageFiles.size(file) ?: return null
            val type = ImageFiles.sniff(file)
            if (type == ImageFiles.Type.JPEG && maxOf(w, h) <= maxSide) {
                FileInputStream(file).use { JPEGFactory.createFromStream(doc, it) }
            } else {
                val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
                val bmp = ImageFiles.decode(file, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1)) ?: return null
                try {
                    val lossless = type == ImageFiles.Type.PNG || bmp.hasAlpha()
                    fromBitmap(bmp, lossless)
                } finally {
                    bmp.recycle()
                }
            }
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "Image too large to embed")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Image could not be embedded (${e.javaClass.simpleName})")
            null
        }
    }

    companion object {
        private const val TAG = "PdfImages"
    }
}
