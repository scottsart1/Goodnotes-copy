package com.inkwell.notes.pdf

import java.io.File
import java.io.FileInputStream

/** Cheap checks on raw bytes before a file is handed to a PDF parser. */
internal object PdfSniffer {
    /** Readers accept the "%PDF-" header anywhere in the first 1024 bytes. */
    const val HEADER_WINDOW = 1024
    private val MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)

    fun looksLikePdf(head: ByteArray, length: Int = head.size): Boolean {
        val n = minOf(length, head.size, HEADER_WINDOW)
        if (n < MAGIC.size) return false
        outer@ for (i in 0..n - MAGIC.size) {
            for (j in MAGIC.indices) if (head[i + j] != MAGIC[j]) continue@outer
            return true
        }
        return false
    }

    fun looksLikePdf(file: File): Boolean {
        if (!file.isFile || file.length() < MAGIC.size) return false
        val buf = ByteArray(HEADER_WINDOW)
        val read = FileInputStream(file).use { input ->
            var total = 0
            while (total < buf.size) {
                val r = input.read(buf, total, buf.size - total)
                if (r < 0) break
                total += r
            }
            total
        }
        return looksLikePdf(buf, read)
    }
}
