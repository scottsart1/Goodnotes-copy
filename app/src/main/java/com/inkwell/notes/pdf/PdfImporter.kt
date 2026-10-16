package com.inkwell.notes.pdf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * Brings outside files into the app: streams a Uri into a private temp file, checks that it
 * really is a PDF, removes encryption that only needs the empty password, repairs damaged
 * cross-reference tables, and reports page sizes. Nothing here touches the library, so a
 * failure leaves no half-imported document behind.
 */
internal class PdfImporter(
    private val context: Context,
    private val engine: PdfRenderEngine,
    private val pdfBox: PdfBoxOps,
    private val workDir: () -> File,
) {
    /** The file's display name ("Lecture 3.pdf"), or null when the provider does not say. */
    suspend fun displayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") return@withContext uri.path?.let { File(it).name }
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && c.moveToFirst()) c.getString(col) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Display name query failed (${e.javaClass.simpleName})")
            null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private suspend fun declaredSize(uri: Uri): Long? = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") return@withContext uri.path?.let { File(it).length() }
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                val col = c.getColumnIndex(OpenableColumns.SIZE)
                if (col >= 0 && c.moveToFirst() && !c.isNull(col)) c.getLong(col) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** A fresh temp file in the PDF work directory. */
    fun tempFile(prefix: String, suffix: String = ".pdf"): File = File.createTempFile(prefix, suffix, workDir())

    /**
     * Streams [uri] into a temp file and checks the PDF header. The caller deletes the file.
     * Cancellation stops the copy and deletes the partial file.
     */
    suspend fun copyToTemp(uri: Uri): File = withContext(Dispatchers.IO) {
        val size = declaredSize(uri)
        val dir = workDir()
        val free = dir.usableSpace
        if (size != null && size > 0 && free > 0 && free < size + SPACE_MARGIN_BYTES) {
            throw PdfErrors.create(context, PdfError.NO_SPACE)
        }
        val tmp = tempFile("import-")
        var ok = false
        try {
            val input = try {
                context.contentResolver.openInputStream(uri)
            } catch (e: FileNotFoundException) {
                null
            } catch (e: SecurityException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            } ?: throw PdfErrors.create(context, PdfError.OPEN_FAILED)
            input.use { inp ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(COPY_BUFFER)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        ensureActive()
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
            if (tmp.length() == 0L) throw PdfErrors.create(context, PdfError.EMPTY_FILE)
            if (!PdfSniffer.looksLikePdf(tmp)) throw PdfErrors.create(context, PdfError.NOT_PDF)
            ok = true
            tmp
        } catch (e: PdfException) {
            throw e
        } catch (e: IOException) {
            throw if (PdfErrors.isOutOfSpace(e)) PdfErrors.create(context, PdfError.NO_SPACE, e)
            else PdfErrors.create(context, PdfError.OPEN_FAILED, e)
        } finally {
            if (!ok) tmp.delete()
        }
    }

    /**
     * Makes [file] openable by PdfRenderer in place and returns its page sizes in points.
     *
     * Encrypted files (PdfRenderer throws SecurityException) are rewritten without security
     * when the empty password opens them; files that need a real password fail with
     * [PdfError.PASSWORD_REQUIRED]. Damaged files get one pdfbox repair pass.
     */
    suspend fun prepare(file: File): List<Pair<Float, Float>> {
        val first = engine.probe(file)
        val sizes = when (first) {
            is PdfRenderEngine.Probe.Ok -> first.sizes
            PdfRenderEngine.Probe.Locked -> {
                rewriteInPlace(file, locked = true)
                (engine.probe(file) as? PdfRenderEngine.Probe.Ok)?.sizes
                    ?: throw PdfErrors.create(context, PdfError.PASSWORD_REQUIRED)
            }
            PdfRenderEngine.Probe.Damaged -> {
                rewriteInPlace(file, locked = false)
                (engine.probe(file) as? PdfRenderEngine.Probe.Ok)?.sizes
                    ?: throw PdfErrors.create(context, PdfError.DAMAGED)
            }
        }
        if (sizes.isEmpty()) throw PdfErrors.create(context, PdfError.NO_PAGES)
        return sizes
    }

    private suspend fun rewriteInPlace(file: File, locked: Boolean) = withContext(Dispatchers.IO) {
        val out = tempFile("rewrite-")
        try {
            pdfBox.rewrite(file, out)
            if (!out.renameTo(file)) {
                out.copyTo(file, overwrite = true)
            }
        } catch (e: InvalidPasswordException) {
            throw PdfErrors.create(context, PdfError.PASSWORD_REQUIRED, e)
        } catch (e: IOException) {
            if (PdfErrors.isOutOfSpace(e)) throw PdfErrors.create(context, PdfError.NO_SPACE, e)
            Log.w(TAG, "PDF rewrite failed (${e.javaClass.simpleName})")
            throw PdfErrors.create(context, if (locked) PdfError.PASSWORD_REQUIRED else PdfError.DAMAGED, e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "PDF rewrite failed (${e.javaClass.simpleName})")
            throw PdfErrors.create(context, PdfError.DAMAGED, e)
        } catch (oom: OutOfMemoryError) {
            throw PdfErrors.create(context, PdfError.TOO_LARGE, oom)
        } finally {
            out.delete()
        }
    }

    companion object {
        private const val TAG = "PdfImport"
        private const val COPY_BUFFER = 64 * 1024
        /** Keep this much free after an import so autosave and the database never run dry. */
        private const val SPACE_MARGIN_BYTES = 32L * 1024 * 1024
    }
}
