package com.inkwell.notes.core.storage

import com.inkwell.notes.core.model.ImageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Per-document size breakdown from a storage scan. */
internal data class StorageScan(
    val totalBytes: Long,
    val audioBytes: Long,
    val pdfBytes: Long,
    val inkBytes: Long,
    val perDocument: Map<String, Long>,
)

/**
 * File work behind the library operations (copies, deletes, scans). Everything runs on
 * [Dispatchers.IO]; writes of whole files go through [FileStore.writeAtomically] so a crash
 * never leaves a half-written file under its final name.
 */
internal class DocumentFiles(private val files: FileStore, private val pages: PageContentStore) {

    /** The document folder without creating it. */
    private fun dir(docId: String) = File(files.root, FileStore.safe(docId))

    fun hasPageContent(docId: String, pageId: String): Boolean {
        val f = File(File(dir(docId), "pages"), "${FileStore.safe(pageId)}.ink")
        return f.exists() || FileStore.backupOf(f).exists()
    }

    fun hasSourcePdf(docId: String): Boolean = File(dir(docId), "source.pdf").let { it.isFile && it.length() > 0 }

    /**
     * Copies one page's content to another page (possibly in another document).
     * [stripAudio] drops recording links that would dangle in the target; [copyAssets] copies the
     * images the page references; [background] is inserted beneath all content.
     * Returns false when the source page has no stored content and nothing was written.
     */
    suspend fun copyPageContent(
        srcDoc: String,
        srcPage: String,
        dstDoc: String,
        dstPage: String,
        stripAudio: Boolean,
        copyAssets: Boolean,
        background: ImageItem? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!hasPageContent(srcDoc, srcPage) && background == null) return@withContext false
        var content = pages.load(srcDoc, srcPage)
        if (stripAudio) content = PageOrdering.withoutAudio(content)
        if (copyAssets && srcDoc != dstDoc) {
            for (assetId in PageOrdering.referencedAssets(content)) copyAsset(srcDoc, dstDoc, assetId)
        }
        if (background != null) content = PageOrdering.withBackground(content, background)
        pages.save(dstDoc, dstPage, content)
        true
    }

    /** Copies an asset unless the target already has a file with that id. */
    private fun copyAsset(srcDoc: String, dstDoc: String, assetId: String) {
        val src = File(File(dir(srcDoc), "assets"), FileStore.safe(assetId))
        if (!src.isFile) return
        val dst = files.assetFile(dstDoc, assetId)
        if (dst.exists()) return
        FileStore.writeAtomically(dst, keepBackup = false) { out -> src.inputStream().use { it.copyTo(out) } }
    }

    suspend fun writeAsset(docId: String, assetId: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        FileStore.writeAtomically(files.assetFile(docId, assetId), keepBackup = false) { it.write(bytes) }
    }

    suspend fun deleteAsset(docId: String, assetId: String) = withContext(Dispatchers.IO) {
        File(File(dir(docId), "assets"), FileStore.safe(assetId)).delete()
    }

    /** Copies the source PDF of [srcDoc] to [dstDoc] (replacing none: only when the target has none). */
    suspend fun copySourcePdf(srcDoc: String, dstDoc: String): Boolean = withContext(Dispatchers.IO) {
        val src = File(dir(srcDoc), "source.pdf")
        if (!src.isFile) return@withContext false
        val dst = files.sourcePdf(dstDoc)
        if (dst.exists()) return@withContext false
        FileStore.writeAtomically(dst, keepBackup = false) { out -> src.inputStream().use { it.copyTo(out) } }
        true
    }

    /** True when both documents have byte-identical source PDFs. */
    suspend fun sameSourcePdf(a: String, b: String): Boolean = withContext(Dispatchers.IO) {
        val fa = File(dir(a), "source.pdf")
        val fb = File(dir(b), "source.pdf")
        fa.isFile && fb.isFile && fa.length() == fb.length() && digest(fa).contentEquals(digest(fb))
    }

    private fun digest(f: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest()
    }

    /**
     * Everything a duplicate needs: page contents (new page ids, recording links removed),
     * assets, the source PDF, typed text and thumbnails. Recordings are not copied.
     * Returns the new paths of copied page thumbnails by new page id, and the cover if copied.
     */
    suspend fun copyForDuplicate(srcDoc: String, dstDoc: String, pageIds: Map<String, String>): DuplicateFiles =
        withContext(Dispatchers.IO) {
            val src = dir(srcDoc)
            for ((from, to) in pageIds) {
                copyPageContent(srcDoc, from, dstDoc, to, stripAudio = true, copyAssets = false)
            }
            File(src, "assets").takeIf { it.isDirectory }?.copyRecursively(files.assetsDir(dstDoc), overwrite = true)
            copyFileIfExists(File(src, "source.pdf"), files.sourcePdf(dstDoc))
            copyFileIfExists(File(src, "typed.json"), files.typedFile(dstDoc))
            val cover = files.thumbnailFile(dstDoc).takeIf { copyFileIfExists(File(src, "thumb.webp"), it) }
            val thumbs = HashMap<String, String>()
            val srcThumbs = File(src, "thumbs")
            if (srcThumbs.isDirectory) {
                for ((from, to) in pageIds) {
                    val dst = files.pageThumbnailFile(dstDoc, to)
                    if (copyFileIfExists(File(srcThumbs, "${FileStore.safe(from)}.webp"), dst)) thumbs[to] = dst.absolutePath
                }
            }
            DuplicateFiles(cover?.absolutePath, thumbs)
        }

    data class DuplicateFiles(val coverPath: String?, val pageThumbnails: Map<String, String>)

    private fun copyFileIfExists(src: File, dst: File): Boolean {
        if (!src.isFile) return false
        FileStore.writeAtomically(dst, keepBackup = false) { out -> src.inputStream().use { it.copyTo(out) } }
        return true
    }

    /** Copies a page thumbnail when one exists; returns its new path. */
    suspend fun copyPageThumbnail(srcDoc: String, srcPage: String, dstDoc: String, dstPage: String): String? =
        withContext(Dispatchers.IO) {
            val src = File(File(dir(srcDoc), "thumbs"), "${FileStore.safe(srcPage)}.webp")
            val dst = files.pageThumbnailFile(dstDoc, dstPage)
            if (copyFileIfExists(src, dst)) dst.absolutePath else null
        }

    /** Deletes a page's content (and its backup) and its cached thumbnail. */
    suspend fun deletePageFiles(docId: String, pageId: String) {
        pages.delete(docId, pageId)
        withContext(Dispatchers.IO) {
            File(File(dir(docId), "thumbs"), "${FileStore.safe(pageId)}.webp").delete()
        }
    }

    suspend fun deleteDocument(docId: String) = withContext(Dispatchers.IO) { files.deleteDocument(docId) }

    suspend fun scan(): StorageScan = withContext(Dispatchers.IO) {
        var audio = 0L
        var pdf = 0L
        var ink = 0L
        var total = 0L
        val perDoc = HashMap<String, Long>()
        for (d in files.root.listFiles().orEmpty()) {
            if (!d.isDirectory) {
                total += d.length()
                continue
            }
            val size = files.sizeOf(d)
            perDoc[d.name] = size
            total += size
            audio += files.sizeOf(File(d, "audio"))
            pdf += files.sizeOf(File(d, "source.pdf"))
            ink += files.sizeOf(File(d, "pages"))
        }
        StorageScan(total, audio, pdf, ink, perDoc)
    }

    fun freeBytes(): Long = files.root.usableSpace

    // ---- Deletion journal: ids whose rows are being deleted, so their folders are removed even
    // if the process dies between the database commit and the file deletion.

    private val journal = File(files.root.parentFile ?: files.root, "pending-deletes.txt")
    private val journalLock = Mutex()

    suspend fun markPendingDelete(docId: String) = journalLock.withLock {
        withContext(Dispatchers.IO) { writeJournal(readJournal() + docId) }
    }

    suspend fun clearPendingDelete(docId: String) = journalLock.withLock {
        withContext(Dispatchers.IO) {
            val ids = readJournal()
            if (docId in ids) writeJournal(ids - docId)
        }
    }

    suspend fun pendingDeletes(): Set<String> = journalLock.withLock { withContext(Dispatchers.IO) { readJournal() } }

    private fun readJournal(): Set<String> =
        if (!journal.isFile) emptySet() else journal.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun writeJournal(ids: Set<String>) {
        if (ids.isEmpty()) {
            journal.delete()
            return
        }
        val bytes = ids.joinToString("\n").encodeToByteArray()
        FileStore.writeAtomically(journal, keepBackup = false) { it.write(bytes) }
    }

}
