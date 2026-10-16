package com.inkwell.notes.export.format

import android.content.Context
import android.graphics.Bitmap
import com.inkwell.notes.R
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.TextSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * Text exports (Markdown, plain text, Word): gathers typed text, text boxes, recognised
 * handwriting, OCR/PDF text, whiteboard stickies and transcripts into an [ExportDocument].
 */
internal class TextExporter(private val context: Context, private val source: DocSource, private val raster: PageRaster) {

    suspend fun document(
        doc: DocumentMeta,
        pageIndices: List<Int>?,
        includeTranscripts: Boolean,
        includePageImages: Boolean,
    ): ExportDocument {
        val typed = if (doc.kind == DocKind.TYPED) source.typed(doc.id) else null
        val pages = if (doc.kind == DocKind.TYPED) emptyList() else source.pages(doc.id)
        val selected = PageRange.resolve(pageIndices, pages.size)
        val rows = if (selected.isEmpty()) emptyList() else source.textRows(doc.id)
        val rowsByPage = rows.filter { it.pageId != null }.groupBy { it.pageId }
        val inputs = ArrayList<PageTextInput>(selected.size)
        for (i in selected) {
            coroutineContext.ensureActive()
            val page = pages[i]
            val content = source.content(doc.id, page)
            val pageRows = rowsByPage[page.id].orEmpty()
            val needsPdfText = page.pdfPageIndex != null &&
                pageRows.none { (it.source == TextSource.PDF_TEXT || it.source == TextSource.OCR) && it.text.isNotBlank() }
            inputs += PageTextInput(
                pageIndex = i,
                texts = content.texts,
                stickies = content.stickies,
                connectorLabels = content.connectors.map { it.label },
                rows = pageRows,
                pdfText = if (needsPdfText) page.pdfPageIndex?.let { source.pdfText(doc.id, it) } else null,
                hasInk = ZOrder.hasInk(content),
            )
        }
        val transcripts = if (includeTranscripts) source.transcripts(doc.id) else emptyList()
        return DocumentTextBuilder.build(
            title = doc.title.ifBlank { context.getString(R.string.export_label_untitled) },
            typed = typed,
            pages = inputs,
            transcripts = transcripts,
            whiteboard = doc.kind == DocKind.WHITEBOARD,
            includePageImages = includePageImages,
            pageLabel = { context.getString(R.string.export_label_page, it + 1) },
            transcriptLabel = { context.getString(R.string.export_label_transcript, it) },
        )
    }

    suspend fun markdown(doc: DocumentMeta, pageIndices: List<Int>?, includeTranscripts: Boolean): String =
        MarkdownWriter.write(document(doc, pageIndices, includeTranscripts, includePageImages = false))

    suspend fun plainText(doc: DocumentMeta, pageIndices: List<Int>?, includeTranscripts: Boolean): String =
        PlainTextWriter.write(document(doc, pageIndices, includeTranscripts, includePageImages = false))

    suspend fun docx(doc: DocumentMeta, pageIndices: List<Int>?, includeTranscripts: Boolean, out: OutputStream) {
        val content = document(doc, pageIndices, includeTranscripts, includePageImages = true)
        val pages: List<PageMeta> = if (doc.kind == DocKind.TYPED) emptyList() else source.pages(doc.id)
        val images = object : DocxImageSource {
            override suspend fun asset(assetId: String): DocxImage? = withContext(Dispatchers.IO) { assetImage(doc.id, assetId) }

            override suspend fun page(pageIndex: Int): DocxImage? {
                val page = pages.getOrNull(pageIndex) ?: return null
                val pageContent = source.content(doc.id, page)
                val (w, _) = raster.displaySize(page, pageContent)
                val width = min(PAGE_IMAGE_MAX_PX, RasterSizing.widthForDpi(w, PAGE_IMAGE_DPI))
                val bmp = raster.render(doc.id, page, pageContent, width, drawPaper = true, opaque = true)
                return try {
                    withContext(Dispatchers.IO) { encode(bmp, png = true) }
                } finally {
                    bmp.recycle()
                }
            }
        }
        withContext(Dispatchers.IO) { DocxWriter(images).write(content, out, doc.createdAt, doc.modifiedAt) }
    }

    /** An asset as Word can embed it: original JPEG/PNG bytes, other formats re-encoded. */
    private fun assetImage(docId: String, assetId: String): DocxImage? {
        val file = source.assetFile(docId, assetId)
        if (!file.isFile) return null
        val (w, h) = ImageFiles.size(file) ?: return null
        return when (ImageFiles.sniff(file)) {
            ImageFiles.Type.JPEG -> if (file.length() <= MAX_EMBED_BYTES) DocxImage(file.readBytes(), "jpeg", w, h) else reencode(file, w, h)
            ImageFiles.Type.PNG -> if (file.length() <= MAX_EMBED_BYTES) DocxImage(file.readBytes(), "png", w, h) else reencode(file, w, h)
            ImageFiles.Type.OTHER -> reencode(file, w, h)
        }
    }

    private fun reencode(file: java.io.File, w: Int, h: Int): DocxImage? {
        val scale = min(1f, MAX_IMAGE_SIDE.toFloat() / maxOf(w, h))
        val bmp = ImageFiles.decode(file, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1)) ?: return null
        return try {
            encode(bmp, png = bmp.hasAlpha())
        } finally {
            bmp.recycle()
        }
    }

    private fun encode(bmp: Bitmap, png: Boolean): DocxImage {
        val bytes = ByteArrayOutputStream().also {
            bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 88, it)
        }.toByteArray()
        return DocxImage(bytes, if (png) "png" else "jpeg", bmp.width, bmp.height)
    }

    companion object {
        private const val PAGE_IMAGE_DPI = 150
        private const val PAGE_IMAGE_MAX_PX = 1600
        private const val MAX_IMAGE_SIDE = 2400
        private const val MAX_EMBED_BYTES = 12L * 1024 * 1024
    }
}
