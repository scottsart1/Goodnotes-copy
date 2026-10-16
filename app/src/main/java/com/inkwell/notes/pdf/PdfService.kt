package com.inkwell.notes.pdf

import android.graphics.Bitmap
import android.net.Uri
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocumentMeta

/**
 * PDF import, rendering and page-level operations. The source PDF is copied into the
 * document folder and never modified; annotations live separately as page content, so
 * they stay editable (non-destructive). Exports burn them in only when asked.
 */
interface PdfService {
    /** Imports a PDF (keeps its original file name as the title) and schedules text extraction/OCR. */
    suspend fun importPdf(uri: Uri, folderId: String? = null): DocumentMeta

    /** Creates a PDF document from images (photos, scans, screenshots), one page per image. */
    suspend fun importImages(uris: List<Uri>, folderId: String? = null, title: String? = null): DocumentMeta

    /** Renders one source-PDF page (white background) at the given pixel size. Cached. */
    suspend fun renderPage(docId: String, pdfPageIndex: Int, widthPx: Int, heightPx: Int): Bitmap?

    /** Page sizes of the source PDF in points. */
    suspend fun pageSizes(docId: String): List<Pair<Float, Float>>

    /** Embedded text of a source-PDF page in reading order ("" for scanned pages). */
    suspend fun pageText(docId: String, pdfPageIndex: Int): String

    /** Appends/inserts all pages of another PDF into [docId] (merge). */
    suspend fun insertPdf(docId: String, uri: Uri, atIndex: Int? = null)

    /** New document containing copies of the given pages (with their annotations). */
    suspend fun extractPages(docId: String, pageIndices: List<Int>, title: String? = null): DocumentMeta

    /** Splits [docId] before [atPageIndex] into a second document; returns it. */
    suspend fun splitDocument(docId: String, atPageIndex: Int): DocumentMeta

    /** Merges [otherDocIds] into a new document, in order. */
    suspend fun mergeDocuments(docIds: List<String>, title: String? = null): DocumentMeta

    /** Releases cached renderers for a document. */
    fun close(docId: String)
}

/** SCAFFOLD STUB — replaced by the PDF implementation. */
class PdfServiceImpl(private val graph: AppGraph) : PdfService {
    private fun stub(): Nothing = throw NotImplementedError("PdfServiceImpl")
    override suspend fun importPdf(uri: Uri, folderId: String?): DocumentMeta = stub()
    override suspend fun importImages(uris: List<Uri>, folderId: String?, title: String?): DocumentMeta = stub()
    override suspend fun renderPage(docId: String, pdfPageIndex: Int, widthPx: Int, heightPx: Int): Bitmap? = null
    override suspend fun pageSizes(docId: String): List<Pair<Float, Float>> = emptyList()
    override suspend fun pageText(docId: String, pdfPageIndex: Int): String = ""
    override suspend fun insertPdf(docId: String, uri: Uri, atIndex: Int?) = stub()
    override suspend fun extractPages(docId: String, pageIndices: List<Int>, title: String?): DocumentMeta = stub()
    override suspend fun splitDocument(docId: String, atPageIndex: Int): DocumentMeta = stub()
    override suspend fun mergeDocuments(docIds: List<String>, title: String?): DocumentMeta = stub()
    override fun close(docId: String) {}
}
