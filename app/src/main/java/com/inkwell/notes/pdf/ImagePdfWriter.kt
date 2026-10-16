package com.inkwell.notes.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Turns photos, scans and screenshots into a PDF, one A4-wide page per image. Images are
 * decoded with [ImageDecoder] (which applies the EXIF orientation), downscaled to at most
 * [ImageSizing.MAX_IMAGE_SIDE] px and embedded as JPEG (photos) or losslessly (screenshots),
 * which keeps documents far smaller than re-encoding every page losslessly.
 */
internal class ImagePdfWriter(private val context: Context, private val pdfBox: PdfBoxOps) {

    data class Result(val pages: Int, val skipped: Int)

    private class Decoded(val bitmap: Bitmap, val mimeType: String?)

    /** Writes the readable images of [uris] to [out]; unreadable ones are skipped. */
    suspend fun write(uris: List<Uri>, out: File): Result = withContext(Dispatchers.IO) {
        var pages = 0
        var skipped = 0
        pdfBox.newDocument().use { doc ->
            for (uri in uris) {
                ensureActive()
                val decoded = decode(uri)
                if (decoded == null) {
                    skipped++
                    continue
                }
                try {
                    addPage(doc, decoded)
                    pages++
                } catch (e: IOException) {
                    Log.w(TAG, "An image could not be added (${e.javaClass.simpleName})")
                    skipped++
                } catch (oom: OutOfMemoryError) {
                    Log.w(TAG, "Out of memory adding an image")
                    skipped++
                } finally {
                    decoded.bitmap.recycle()
                }
            }
            if (pages == 0) throw PdfErrors.create(context, PdfError.IMAGES_UNREADABLE)
            try {
                out.delete()
                doc.save(out)
            } catch (e: IOException) {
                throw PdfErrors.forWrite(context, e)
            }
        }
        if (skipped > 0) Log.w(TAG, "Skipped $skipped unreadable image(s)")
        Result(pages, skipped)
    }

    private fun addPage(doc: PDDocument, decoded: Decoded) {
        val bitmap = decoded.bitmap
        val (w, h) = ImageSizing.pageSizeFor(bitmap.width, bitmap.height)
        val image = if (ImageSizing.preferJpeg(decoded.mimeType)) {
            JPEGFactory.createFromImage(doc, bitmap, ImageSizing.JPEG_QUALITY)
        } else {
            LosslessFactory.createFromImage(doc, bitmap)
        }
        val page = PDPage(PDRectangle(w, h))
        PDPageContentStream(doc, page).use { it.drawImage(image, 0f, 0f, w, h) }
        // Added only once its content is written, so a failure never leaves a blank page.
        doc.addPage(page)
    }

    private fun decode(uri: Uri): Decoded? = try {
        var mime: String? = null
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val raw = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            mime = info.mimeType
            val target = ImageSizing.downscale(info.size.width, info.size.height)
            if (target.width != info.size.width || target.height != info.size.height) {
                decoder.setTargetSize(target.width, target.height)
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // PDF pages are plain DeviceRGB; wide-gamut and HDR sources are mapped to sRGB.
            runCatching { decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB)) }
        }
        Decoded(opaque8888(raw), mime)
    } catch (e: IOException) {
        Log.w(TAG, "An image could not be decoded (${e.javaClass.simpleName})")
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "An image could not be decoded (${e.javaClass.simpleName})")
        null
    } catch (oom: OutOfMemoryError) {
        Log.w(TAG, "Out of memory decoding an image")
        null
    }

    /** Transparent areas become white paper; wide-gamut configs become plain ARGB_8888. */
    private fun opaque8888(src: Bitmap): Bitmap {
        if (src.config == Bitmap.Config.ARGB_8888 && !src.hasAlpha()) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        out.setHasAlpha(false)
        src.recycle()
        return out
    }

    companion object {
        private const val TAG = "ImagePdf"
    }
}
