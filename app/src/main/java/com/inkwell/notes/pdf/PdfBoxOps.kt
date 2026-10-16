package com.inkwell.notes.pdf

import android.content.Context
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import java.io.File
import java.io.IOException

/**
 * Structural PDF work with pdfbox-android: unlocking, repairing, appending and building
 * page subsets. Every function blocks; callers run them on [kotlinx.coroutines.Dispatchers.IO].
 * Documents spill to temp files in [tempDir] beyond [MAIN_MEMORY_BYTES], so large PDFs do not
 * exhaust the heap.
 */
internal class PdfBoxOps(private val context: Context, private val tempDir: () -> File) {
    @Volatile private var initialised = false

    /** PDFBoxResourceLoader must be initialised once before fonts or text are touched. */
    fun ensureInit() {
        if (initialised) return
        synchronized(this) {
            if (!initialised) {
                PDFBoxResourceLoader.init(context.applicationContext)
                initialised = true
            }
        }
    }

    private fun memory(): MemoryUsageSetting =
        MemoryUsageSetting.setupMixed(MAIN_MEMORY_BYTES).setTempDir(tempDir())

    /**
     * Opens [file]. Encrypted files open with the empty user password (owner-password-only
     * files); a real password makes this throw
     * [com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException].
     */
    fun open(file: File): PDDocument {
        ensureInit()
        val doc = PDDocument.load(file, "", memory())
        if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
        return doc
    }

    fun newDocument(): PDDocument {
        ensureInit()
        return PDDocument(memory())
    }

    /**
     * Rewrites [file] into [out] with all security removed. pdfbox's lenient parser also
     * rebuilds broken cross-reference tables, so this doubles as a repair pass.
     */
    fun rewrite(file: File, out: File) {
        open(file).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            doc.save(out)
        }
    }

    /**
     * Writes [existing] followed by every page of [incoming] to [out]. Returns the number of
     * pages [existing] had, which is the pdfPageIndex of the first appended page.
     */
    fun append(existing: File, incoming: File, out: File): Int = open(incoming).use { src ->
        val added = src.numberOfPages
        try {
            appendInto(existing, out, added) { dest ->
                PDFMergerUtility().apply { isIgnoreAcroFormErrors = true }.appendDocument(dest, src)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Full merge failed (${e.javaClass.simpleName}); copying pages only")
            appendInto(existing, out, added) { dest -> for (i in 0 until added) importPage(dest, src.getPage(i)) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Full merge failed (${e.javaClass.simpleName}); copying pages only")
            appendInto(existing, out, added) { dest -> for (i in 0 until added) importPage(dest, src.getPage(i)) }
        }
    }

    private inline fun appendInto(existing: File, out: File, added: Int, block: (PDDocument) -> Unit): Int =
        open(existing).use { dest ->
            val before = dest.numberOfPages
            block(dest)
            if (dest.numberOfPages != before + added) throw IOException("Unexpected page count after merge")
            dest.isAllSecurityToBeRemoved = true
            out.delete()
            dest.save(out)
            before
        }

    /**
     * Writes a new PDF to [out] whose page i is page `pages[i].second` of file `pages[i].first`.
     * Each source file is opened once and kept open until the result is saved (pdfbox shares
     * resources between them until then).
     */
    fun subset(pages: List<Pair<File, Int>>, out: File) {
        val open = LinkedHashMap<File, PDDocument>()
        try {
            newDocument().use { dest ->
                for ((file, index) in pages) {
                    val src = open.getOrPut(file) { open(file) }
                    if (index !in 0 until src.numberOfPages) throw IOException("Page $index out of range")
                    importPage(dest, src.getPage(index))
                }
                out.delete()
                dest.save(out)
            }
        } finally {
            open.values.forEach { runCatching { it.close() } }
        }
    }

    /**
     * Copies one page with its inherited attributes. Links and annotation back-references to
     * pages that are not part of the result are cut, so the whole source document is not
     * dragged along into the new file.
     */
    private fun importPage(dest: PDDocument, page: PDPage): PDPage {
        val imported = dest.importPage(page)
        val resources = page.resources
        if (resources != null && !page.cosObject.containsKey(COSName.RESOURCES)) imported.resources = resources
        try {
            for (annotation in imported.annotations) {
                if (annotation is PDAnnotationLink) {
                    val destination = annotation.destination ?: (annotation.action as? PDActionGoTo)?.destination
                    if (destination is PDPageDestination) destination.setPage(null)
                }
                annotation.setPage(null)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Annotations of a page could not be processed (${e.javaClass.simpleName})")
        }
        return imported
    }

    companion object {
        private const val TAG = "PdfBoxOps"
        const val MAIN_MEMORY_BYTES = 48L * 1024 * 1024
    }
}
