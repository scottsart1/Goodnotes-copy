package com.inkwell.notes.export.format

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.TypedDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream

/** Reads everything an export needs about a document, off the main thread. */
internal class DocSource(private val graph: AppGraph) {

    suspend fun document(docId: String): DocumentMeta = withContext(Dispatchers.IO) {
        graph.library.getDocument(docId)
            ?: graph.db.documents().get(docId)?.toModel()
            ?: throw ExportErrors.of(graph.context, R.string.export_err_not_found)
    }

    suspend fun pages(docId: String): List<PageMeta> = withContext(Dispatchers.IO) {
        graph.library.getPages(docId).ifEmpty { graph.db.pages().forDocument(docId).map { it.toModel() } }
            .sortedBy { it.index }
    }

    suspend fun content(docId: String, page: PageMeta): PageContent = graph.pageStore.load(docId, page.id)

    suspend fun typed(docId: String): TypedDocument = withContext(Dispatchers.IO) {
        val f = graph.files.typedFile(docId)
        if (!f.isFile) return@withContext TypedDocument()
        try {
            typedJson.decodeFromString(TypedDocument.serializer(), f.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable typed document (${e.javaClass.simpleName})")
            TypedDocument()
        }
    }

    suspend fun textRows(docId: String): List<TextRow> = withContext(Dispatchers.IO) {
        graph.db.textIndex().forDocument(docId).map { TextRow(it.pageId, it.source, it.text, TextRow.parseBounds(it.bounds), it.confidence) }
    }

    suspend fun transcripts(docId: String): List<TranscriptInput> = withContext(Dispatchers.IO) {
        val fallback = graph.context.getString(R.string.export_label_recording)
        graph.db.recordings().forDocument(docId).mapNotNull { rec ->
            val segments = graph.db.transcripts().forRecording(rec.id).map { it.toModel() }
            if (segments.isEmpty()) null else TranscriptInput(rec.title.ifBlank { fallback }, segments)
        }
    }

    /** Embedded text of a source-PDF page, or null when unavailable. */
    suspend fun pdfText(docId: String, pdfPageIndex: Int): String? = try {
        graph.pdf.pageText(docId, pdfPageIndex).takeIf { it.isNotBlank() }
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        null
    }

    /** Background of a PDF page at the given pixel size (shared with the PDF cache: never recycle). */
    suspend fun pdfBackground(docId: String, page: PageMeta, widthPx: Int, heightPx: Int): Bitmap? {
        val idx = page.pdfPageIndex ?: return null
        return try {
            graph.pdf.renderPage(docId, idx, widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "PDF background unavailable (${t.javaClass.simpleName})")
            null
        }
    }

    fun assetFile(docId: String, assetId: String): File = graph.files.assetFile(docId, assetId)

    companion object {
        private const val TAG = "ExportSource"
        val typedJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }
}

/** Image file helpers shared by the raster, PDF and Word exports. */
internal object ImageFiles {
    enum class Type { JPEG, PNG, OTHER }

    fun sniff(file: File): Type = try {
        FileInputStream(file).use { input ->
            val h = ByteArray(8)
            val n = input.read(h)
            when {
                n >= 3 && h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() && h[2] == 0xFF.toByte() -> Type.JPEG
                n >= 8 && h[0] == 0x89.toByte() && h[1] == 'P'.code.toByte() && h[2] == 'N'.code.toByte() && h[3] == 'G'.code.toByte() -> Type.PNG
                else -> Type.OTHER
            }
        }
    } catch (e: Exception) {
        Type.OTHER
    }

    /** Pixel size of an image file without decoding it; null when it is not an image. */
    fun size(file: File): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    /** Decodes [file] at the smallest power-of-two reduction that still covers [targetW] x [targetH]. */
    fun decode(file: File, targetW: Int, targetH: Int): Bitmap? {
        if (!file.isFile) return null
        val (w, h) = size(file) ?: return null
        val o = BitmapFactory.Options().apply {
            inSampleSize = RasterSizing.sampleSize(w, h, targetW.coerceAtLeast(1), targetH.coerceAtLeast(1))
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            BitmapFactory.decodeFile(file.path, o)
        } catch (oom: OutOfMemoryError) {
            o.inSampleSize *= 2
            runCatching { BitmapFactory.decodeFile(file.path, o) }.getOrNull()
        }
    }
}
