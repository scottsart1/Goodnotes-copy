package com.inkwell.notes.export.format

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.ink.render.PageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Renders whole pages to bitmaps with the shared [PageRenderer]: paper or PDF background,
 * images and vector content, in the orientation the editor shows (page rotation applied).
 * Unbounded (whiteboard) pages render their content with a small margin.
 */
internal class PageRaster(private val source: DocSource) {

    /** Size in points of what [render] draws for [page] (after rotation). */
    fun displaySize(page: PageMeta, content: PageContent): Pair<Float, Float> {
        if (page.isInfinite) {
            val b = boardBounds(content)
            return (b[2] - b[0]) to (b[3] - b[1])
        }
        val rot = PdfGeometry.normalizeRotation(page.rotation)
        return if (rot == 90 || rot == 270) page.heightPt to page.widthPt else page.widthPt to page.heightPt
    }

    /**
     * Draws [page] [widthPx] pixels wide (bounded by [RasterSizing]). [drawPaper] false leaves
     * paper and PDF pages out; [opaque] fills the result with the paper colour or white so
     * formats without alpha (JPEG, thumbnails) never show black.
     */
    suspend fun render(
        docId: String,
        page: PageMeta,
        content: PageContent,
        widthPx: Int,
        drawPaper: Boolean = true,
        opaque: Boolean = true,
    ): Bitmap {
        val (dw, dh) = displaySize(page, content)
        val size = RasterSizing.fit(dw, dh, widthPx)
        val scale = size.width / dw.coerceAtLeast(1f)

        val background = if (drawPaper && !page.isInfinite && page.pdfPageIndex != null) {
            source.pdfBackground(docId, page, ceil(page.widthPt * scale).toInt(), ceil(page.heightPt * scale).toInt())
        } else null
        val images = decodeImages(docId, content.images, scale)
        try {
            return withContext(Dispatchers.Default) {
                val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                if (opaque) canvas.drawColor(if (drawPaper) page.paper.color or 0xFF000000.toInt() else Color.WHITE)
                canvas.scale(scale, scale)
                if (page.isInfinite) {
                    val b = boardBounds(content)
                    if (drawPaper && !opaque) canvas.drawColor(page.paper.color)
                    canvas.translate(-b[0], -b[1])
                    PageRenderer.drawContent(canvas, content, { images[it] })
                } else {
                    when (PdfGeometry.normalizeRotation(page.rotation)) {
                        90 -> { canvas.translate(page.heightPt, 0f); canvas.rotate(90f) }
                        180 -> { canvas.translate(page.widthPt, page.heightPt); canvas.rotate(180f) }
                        270 -> { canvas.translate(0f, page.widthPt); canvas.rotate(270f) }
                    }
                    PageRenderer.drawPage(canvas, page, content, background, { images[it] }, drawPaper)
                }
                bmp
            }
        } finally {
            images.values.forEach { it.recycle() }
        }
    }

    /** Decodes each referenced image once, at the resolution it is drawn at. */
    private suspend fun decodeImages(docId: String, items: List<ImageItem>, scale: Float): Map<String, Bitmap> =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext emptyMap()
            val need = HashMap<String, Pair<Int, Int>>()
            for (it in items) {
                val visibleW = (1f - it.cropLeft - it.cropRight).coerceIn(0.05f, 1f)
                val visibleH = (1f - it.cropTop - it.cropBottom).coerceIn(0.05f, 1f)
                val w = (it.width * scale / visibleW).roundToInt().coerceIn(1, 8192)
                val h = (it.height * scale / visibleH).roundToInt().coerceIn(1, 8192)
                val prev = need[it.assetId]
                need[it.assetId] = if (prev == null) w to h else maxOf(prev.first, w) to maxOf(prev.second, h)
            }
            val out = HashMap<String, Bitmap>()
            for ((asset, wh) in need) {
                ImageFiles.decode(source.assetFile(docId, asset), wh.first, wh.second)?.let { out[asset] = it }
            }
            out
        }

    companion object {
        const val BOARD_MARGIN = 24f

        /** Content bounds of a board with a margin; a blank board is a 4:3 sheet. */
        fun boardBounds(content: PageContent): FloatArray {
            val b = ZOrder.contentBounds(content) ?: return floatArrayOf(0f, 0f, 800f, 600f)
            return floatArrayOf(b[0] - BOARD_MARGIN, b[1] - BOARD_MARGIN, b[2] + BOARD_MARGIN, b[3] + BOARD_MARGIN)
        }
    }
}
