package com.inkwell.notes.pdf

/** Cleanup of text extracted from a PDF page; pure so it is unit-testable. */
internal object PdfText {
    /**
     * Normalises line endings, drops NULs, trims trailing spaces of every line and collapses
     * runs of blank lines to one, without touching the words themselves.
     */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return raw
        val lines = raw.replace("\u0000", "").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = StringBuilder(raw.length)
        var blank = 0
        for (line in lines) {
            val t = line.replace(' ', ' ').trimEnd()
            if (t.isBlank()) {
                blank++
                if (blank > 1) continue
            } else {
                blank = 0
            }
            if (out.isNotEmpty()) out.append('\n')
            out.append(t)
        }
        return out.toString().trim()
    }

    fun cacheKey(docId: String, pdfPageIndex: Int): String = "$docId\u0000$pdfPageIndex"

    fun isKeyOf(key: String, docId: String): Boolean = key.startsWith("$docId\u0000")
}
