package com.inkwell.notes.export.format

import android.content.Context
import android.util.Log
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.export.ExportOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PageMode
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.Calendar
import kotlin.coroutines.coroutineContext

/** PDFBox needs its resource loader initialised once per process before fonts are touched. */
internal object PdfBoxInit {
    @Volatile private var ready = false

    fun ensure(context: Context) {
        if (ready) return
        synchronized(this) {
            if (!ready) {
                PDFBoxResourceLoader.init(context.applicationContext)
                ready = true
            }
        }
    }

    fun memory(context: Context): MemoryUsageSetting =
        MemoryUsageSetting.setupTempFileOnly().setTempDir(File(context.cacheDir, "pdfbox").apply { mkdirs() })
}

/**
 * PDF export with PDFBox, one page at a time with temp-file-backed buffers:
 * - pages of an imported PDF are copied as the original page (text stays selectable) and
 *   the ink is drawn on top in an appended, state-isolated content stream;
 * - paper pages get a vector paper background; whiteboards a page fitted to the content;
 * - annotations are flattened, or kept as Ink/FreeText annotations where the format allows;
 * - optional invisible text from recognised handwriting/OCR, and an outline.
 */
internal class PdfExporter(private val graph: AppGraph, private val source: DocSource) {
    private val context: Context get() = graph.context

    suspend fun export(doc: DocumentMeta, options: ExportOptions, out: OutputStream, progress: (Int, Int) -> Unit) =
        withContext(Dispatchers.IO) {
            PdfBoxInit.ensure(context)
            if (doc.kind == DocKind.TYPED) exportTyped(doc, options, out, progress) else exportPages(doc, options, out, progress)
        }

    // ------------------------------------------------------------------ Page documents

    private suspend fun exportPages(doc: DocumentMeta, options: ExportOptions, out: OutputStream, progress: (Int, Int) -> Unit) {
        val pages = source.pages(doc.id)
        val indices = PageRange.resolve(options.pageIndices, pages.size)
        if (indices.isEmpty()) throw ExportErrors.of(context, R.string.export_err_no_pages)
        val rowsByPage: Map<String?, List<TextRow>> =
            if (options.searchableText) source.textRows(doc.id).filter { it.pageId != null && it.bounds != null }.groupBy { it.pageId }
            else emptyMap()

        val target = PDDocument(PdfBoxInit.memory(context))
        var sourcePdf: PDDocument? = null
        var sourceTried = false
        val fontsLazy = lazy { PdfFonts(target, FontFiles.regular(context), FontFiles.bold(context)) }
        val images = PdfImages(target, source::assetFile, options.jpegQuality, maxImageSide(options.dpi))
        val res = PdfDocResources(target, { fontsLazy.value }, images)
        val annotations = PdfAnnotations(res, doc.id)
        val now = Calendar.getInstance()

        fun openSource(): PDDocument? {
            if (!sourceTried) {
                sourceTried = true
                val f = graph.files.sourcePdf(doc.id)
                sourcePdf = if (f.isFile) {
                    try {
                        PDDocument.load(f, PdfBoxInit.memory(context))
                    } catch (e: Exception) {
                        Log.w(TAG, "Source PDF unreadable (${e.javaClass.simpleName}); using rendered pages")
                        null
                    }
                } else null
            }
            return sourcePdf
        }

        try {
            indices.forEachIndexed { n, idx ->
                coroutineContext.ensureActive()
                val page = pages[idx]
                val content = source.content(doc.id, page)
                writePage(target, page, content, doc, options, ::openSource, res, annotations, rowsByPage[page.id].orEmpty(), now)
                progress(n + 1, indices.size)
            }
            if (options.includeOutline) {
                val entries = graph.db.outline().forDocument(doc.id).map { it.toModel() }
                addOutline(target, OutlinePlan.build(entries, pages, indices) { context.getString(R.string.export_label_page, it + 1) })
            }
            setInfo(target, doc)
            coroutineContext.ensureActive()
            target.save(out)
        } finally {
            runCatching { target.close() }
            runCatching { sourcePdf?.close() }
            if (fontsLazy.isInitialized()) fontsLazy.value.close()
        }
    }

    private suspend fun writePage(
        target: PDDocument,
        page: PageMeta,
        content: PageContent,
        doc: DocumentMeta,
        options: ExportOptions,
        openSource: () -> PDDocument?,
        res: PdfDocResources,
        annotations: PdfAnnotations,
        rows: List<TextRow>,
        now: Calendar,
    ) {
        val pdPage: PDPage
        val toUser: Affine
        var paperBox: FloatArray? = null       // vector paper (left, top, right, bottom) in page points
        var boardFill: FloatArray? = null      // whiteboard background rectangle
        var raster: PDImageXObject? = null     // rendered PDF page when the original is unreadable
        var hasTextLayer = false

        val srcPage = if (!page.isInfinite && page.pdfPageIndex != null && options.includeBackground) {
            openSource()?.let { src -> page.pdfPageIndex.takeIf { it in 0 until src.numberOfPages }?.let { src.getPage(it) } }
        } else null

        when {
            page.isInfinite -> {
                val b = PageRaster.boardBounds(content)
                val (w, h, t) = PdfGeometry.boardPage(b[0], b[1], b[2], b[3], 0f)
                pdPage = PDPage(PDRectangle(w, h))
                target.addPage(pdPage)
                toUser = t
                if (options.includeBackground) boardFill = b
            }
            srcPage != null -> {
                pdPage = target.importPage(srcPage)
                if (!pdPage.cosObject.containsKey(COSName.RESOURCES)) srcPage.resources?.let { pdPage.resources = it }
                val box = pdPage.cropBox
                val r0 = srcPage.rotation
                toUser = PdfGeometry.displayToUser(box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY, r0, page.widthPt, page.heightPt)
                pdPage.rotation = PdfGeometry.combinedRotation(r0, page.rotation)
                hasTextLayer = true
            }
            else -> {
                pdPage = PDPage(PDRectangle(page.widthPt.coerceAtLeast(1f), page.heightPt.coerceAtLeast(1f)))
                target.addPage(pdPage)
                pdPage.rotation = PdfGeometry.normalizeRotation(page.rotation)
                toUser = PdfGeometry.pageToUser(page.widthPt, page.heightPt)
                if (options.includeBackground) {
                    if (page.pdfPageIndex != null) {
                        raster = rasterBackground(doc.id, page, options, res)
                    }
                    paperBox = floatArrayOf(0f, 0f, page.widthPt, page.heightPt)
                }
            }
        }

        val flatten = options.flattenAnnotations
        PDPageContentStream(target, pdPage, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.saveGraphicsState()
            cs.transform(PdfCanvas.matrix(toUser))
            val canvas = PdfCanvas(cs, res, doc.id)
            paperBox?.let { canvas.paper(page.paper, it[2], it[3]) }
            boardFill?.let { b -> canvas.fill(page.paper.color or 0xFF000000.toInt()) { cs.addRect(b[0], b[1], b[2] - b[0], b[3] - b[1]) } }
            raster?.let { cs.drawImage(it, Matrix(page.widthPt, 0f, 0f, -page.heightPt, 0f, page.heightPt)) }
            if (flatten) canvas.items(content) else canvas.items(content) { !isAnnotatable(it) }
            if (options.searchableText) {
                val sources = if (hasTextLayer) SEARCH_SOURCES else SEARCH_SOURCES + TextSource.PDF_TEXT
                for (row in rows) {
                    if (row.source !in sources) continue
                    val b = row.bounds ?: continue
                    canvas.invisibleText(row.text, b[0], b[1], b[2], b[3])
                }
            }
            cs.restoreGraphicsState()
        }

        if (!flatten) {
            val added = ArrayList<PDAnnotation>()
            for (item in ZOrder.ordered(content)) {
                if (!isAnnotatable(item)) continue
                val a = when (item) {
                    is Stroke -> annotations.ink(item, toUser, now)
                    is TextItem -> annotations.freeText(item, toUser, now)
                    else -> null
                }
                if (a != null) {
                    a.page = pdPage
                    added += a
                }
            }
            if (added.isNotEmpty()) {
                // A fresh list (and array): an imported page shares /Annots with the source file.
                pdPage.annotations = ArrayList(pdPage.annotations) + added
            }
        }
    }

    /** The page of an unreadable source PDF, rendered by the PDF service and embedded as an image. */
    private suspend fun rasterBackground(docId: String, page: PageMeta, options: ExportOptions, res: PdfDocResources): PDImageXObject? {
        val w = RasterSizing.widthForDpi(page.widthPt, options.dpi)
        val size = RasterSizing.fit(page.widthPt, page.heightPt, w)
        val bmp = source.pdfBackground(docId, page, size.width, size.height) ?: return null
        return try {
            res.images.fromBitmap(bmp, lossless = false) // shared with the PDF cache: not recycled
        } catch (e: Exception) {
            Log.w(TAG, "Could not embed rendered page (${e.javaClass.simpleName})")
            null
        }
    }

    private fun isAnnotatable(item: Any): Boolean = when (item) {
        is Stroke -> item.pointCount > 0
        is TextItem -> item.rotation == 0f && item.text.isNotBlank()
        else -> false
    }

    // ------------------------------------------------------------------ Typed documents

    private suspend fun exportTyped(doc: DocumentMeta, options: ExportOptions, out: OutputStream, progress: (Int, Int) -> Unit) {
        val typed = source.typed(doc.id)
        val layout = withContext(Dispatchers.Default) {
            TypedLayout(context, doc.title, TypedBlocks.convert(typed), { source.assetFile(doc.id, it) })
        }
        val indices = PageRange.resolve(options.pageIndices, layout.pageCount)
        if (indices.isEmpty()) throw ExportErrors.of(context, R.string.export_err_no_pages)
        val tmp = File.createTempFile("typed", ".pdf", File(context.cacheDir, "pdfbox").apply { mkdirs() })
        try {
            val pdf = android.graphics.pdf.PdfDocument()
            try {
                indices.forEachIndexed { n, i ->
                    coroutineContext.ensureActive()
                    val info = android.graphics.pdf.PdfDocument.PageInfo.Builder(layout.pageWidth.toInt(), layout.pageHeight.toInt(), n + 1).create()
                    val p = pdf.startPage(info)
                    layout.draw(p.canvas, i, IMAGE_DPI / 72f)
                    pdf.finishPage(p)
                    progress(n + 1, indices.size)
                }
                tmp.outputStream().use { pdf.writeTo(it) }
            } finally {
                pdf.close()
            }
            // Title and outline are added with PDFBox; the page content stays as written.
            PDDocument.load(tmp, PdfBoxInit.memory(context)).use { pd ->
                if (options.includeOutline) {
                    val exportIndex = indices.withIndex().associate { it.value to it.index }
                    val nodes = layout.headings.mapNotNull { h -> exportIndex[h.pageIndex]?.let { OutlineMark(h.level, h.text, it) } }
                    addOutline(pd, nestHeadings(nodes))
                }
                setInfo(pd, doc)
                pd.save(out)
            }
        } finally {
            tmp.delete()
        }
    }

    private data class OutlineMark(val level: Int, val title: String, val exportIndex: Int)

    /** Nests headings by level (H2 under the preceding H1, ...). */
    private fun nestHeadings(marks: List<OutlineMark>): List<OutlineNode> {
        class Node(val mark: OutlineMark, val children: MutableList<Node> = ArrayList())
        val roots = ArrayList<Node>()
        val stack = ArrayList<Node>()
        for (m in marks) {
            if (m.title.isBlank()) continue
            val node = Node(m)
            while (stack.isNotEmpty() && stack.last().mark.level >= m.level) stack.removeAt(stack.size - 1)
            if (stack.isEmpty()) roots += node else stack.last().children += node
            stack += node
        }
        fun convert(n: Node): OutlineNode = OutlineNode(n.mark.title.trim(), n.mark.exportIndex, n.children.map(::convert))
        return roots.map(::convert)
    }

    // ------------------------------------------------------------------ Document level

    private fun addOutline(target: PDDocument, nodes: List<OutlineNode>) {
        if (nodes.isEmpty()) return
        val outline = PDDocumentOutline()
        fun item(n: OutlineNode): PDOutlineItem? {
            if (n.exportIndex !in 0 until target.numberOfPages) return null
            val it = PDOutlineItem()
            it.title = n.title.take(500)
            it.destination = PDPageFitDestination().apply { page = target.getPage(n.exportIndex) }
            n.children.forEach { c -> item(c)?.let { child -> it.addLast(child) } }
            return it
        }
        nodes.forEach { n -> item(n)?.let { outline.addLast(it) } }
        target.documentCatalog.documentOutline = outline
        target.documentCatalog.pageMode = PageMode.USE_OUTLINES
    }

    private fun setInfo(target: PDDocument, doc: DocumentMeta) {
        val info = target.documentInformation
        info.title = doc.title
        info.creator = "Inkwell"
        info.producer = "Inkwell"
        if (doc.tags.isNotEmpty()) info.keywords = doc.tags.joinToString(", ")
        info.creationDate = Calendar.getInstance().apply { timeInMillis = doc.createdAt }
        info.modificationDate = Calendar.getInstance().apply { timeInMillis = doc.modifiedAt.coerceAtLeast(doc.createdAt) }
    }

    companion object {
        private const val TAG = "PdfExport"
        private const val IMAGE_DPI = 200f
        private val SEARCH_SOURCES = setOf(TextSource.HANDWRITING, TextSource.OCR)

        /** Longest side of embedded images for the chosen quality (resolution). */
        fun maxImageSide(dpi: Int): Int = when {
            dpi >= 300 -> 4096
            dpi >= 200 -> 2560
            else -> 1600
        }
    }
}
