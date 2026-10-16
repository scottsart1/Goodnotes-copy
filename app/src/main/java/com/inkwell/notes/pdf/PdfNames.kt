package com.inkwell.notes.pdf

/**
 * Titles for imported and derived PDF documents. Imported files keep their file name (without
 * the extension); names that carry no meaning (scanner output, timestamps, "document") return
 * null so the document gets the standard "YYYYMMDD_Untitled" name and auto-naming applies.
 */
internal object PdfNames {
    const val MAX_TITLE_CHARS = 120

    private val CONTROL = Regex("[\\p{Cntrl}\\u2028\\u2029\\uFEFF]")
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|]")
    private val WHITESPACE = Regex("\\s+")
    private val PDF_EXTENSION = Regex("(?i)\\.pdf$")
    private val IMAGE_EXTENSION = Regex("(?i)\\.(jpe?g|png|webp|heic|heif|gif|bmp|tiff?|avif)$")
    private val DIGITS_ONLY = Regex("^[0-9]{10,}$")
    private val UUID_LIKE = Regex("^(?i)[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$")
    private val MEANINGLESS = setOf(
        "", "untitled", "document", "download", "downloads", "file", "scan", "scanned", "pdf",
        "new document", "temp", "tmp", "image", "images", "photo", "untitled document",
    )

    /** Collapses whitespace and control characters, removes characters illegal in file names, limits length. */
    fun clean(raw: String?): String? {
        if (raw == null) return null
        var s = raw.replace(CONTROL, " ").replace(ILLEGAL, " ").replace(WHITESPACE, " ").trim()
        s = s.trim('.', ' ', '-', '_')
        if (s.isEmpty()) return null
        if (s.length > MAX_TITLE_CHARS) {
            s = s.take(MAX_TITLE_CHARS)
            val cut = s.lastIndexOf(' ')
            if (cut >= MAX_TITLE_CHARS / 2) s = s.substring(0, cut)
            s = s.trimEnd('.', ' ', '-', '_')
        }
        return s.ifEmpty { null }
    }

    /**
     * Title for an imported PDF from its display name: "Lecture 3 - Markets.pdf" becomes
     * "Lecture 3 - Markets". Returns null when nothing meaningful is left.
     */
    fun titleFromDisplayName(displayName: String?): String? {
        if (displayName == null) return null
        var base = displayName.trim().substringAfterLast('/').substringAfterLast('\\').trim()
        base = base.replace(PDF_EXTENSION, "").trim()
        // "notes.pdf.pdf" from some download managers.
        base = base.replace(PDF_EXTENSION, "").trim()
        val title = clean(base) ?: return null
        return title.takeUnless { isMeaningless(it) }
    }

    /** Title for a document made from an image's display name (same rules, image extensions). */
    fun titleFromImageName(displayName: String?): String? {
        if (displayName == null) return null
        val base = displayName.trim().substringAfterLast('/').substringAfterLast('\\').replace(IMAGE_EXTENSION, "")
        return clean(base)?.takeUnless { isMeaningless(it) }
    }

    /** Names like "1729081234567", UUIDs, "document" or "scan" say nothing about the content. */
    fun isMeaningless(title: String): Boolean {
        val t = title.trim()
        return t.lowercase() in MEANINGLESS || DIGITS_ONLY.matches(t) || UUID_LIKE.matches(t)
    }

    /** True for files written by the ML Kit document scanner into this app's cache. */
    fun isScannerOutput(uriString: String): Boolean {
        val s = uriString.lowercase()
        return s.contains("mlkit_docscan") || s.contains("mlkit-docscan") || s.contains("document_scanner")
    }

    /**
     * Human page-range label from 0-based indices: [2,3,4] → "pages 3-5", [2] → "page 3",
     * [0,2,3] → "pages 1, 3-4". More than three ranges become "12 pages".
     */
    fun pageLabel(indices: Collection<Int>): String {
        val sorted = indices.filter { it >= 0 }.distinct().sorted()
        if (sorted.isEmpty()) return "pages"
        if (sorted.size == 1) return "page ${sorted[0] + 1}"
        val ranges = ArrayList<IntRange>()
        var start = sorted[0]
        var prev = sorted[0]
        for (i in 1 until sorted.size) {
            val v = sorted[i]
            if (v == prev + 1) {
                prev = v
            } else {
                ranges += start..prev
                start = v
                prev = v
            }
        }
        ranges += start..prev
        if (ranges.size > 3) return "${sorted.size} pages"
        return "pages " + ranges.joinToString(", ") { r ->
            if (r.first == r.last) "${r.first + 1}" else "${r.first + 1}-${r.last + 1}"
        }
    }

    /** "<source title> (pages 3-5)" for extracted pages, kept within [MAX_TITLE_CHARS]. */
    fun extractTitle(sourceTitle: String, indices: Collection<Int>): String {
        val suffix = " (${pageLabel(indices)})"
        val base = (clean(sourceTitle) ?: "Pages").let { b ->
            if (b.length + suffix.length > MAX_TITLE_CHARS) b.take(MAX_TITLE_CHARS - suffix.length).trimEnd() else b
        }
        return base + suffix
    }

    /** "A + B" for two documents, "A + 2 more" for more, kept within [MAX_TITLE_CHARS]. */
    fun mergedTitle(titles: List<String>): String {
        val cleaned = titles.mapNotNull { clean(it) }
        val first = cleaned.firstOrNull() ?: return "Merged document"
        val more = " + ${cleaned.size - 1} more"
        var suffix = when {
            cleaned.size == 1 -> " (merged)"
            cleaned.size == 2 && !cleaned[0].equals(cleaned[1], ignoreCase = true) -> " + ${cleaned[1]}"
            else -> more
        }
        if (suffix.length > MAX_TITLE_CHARS / 2) suffix = more
        val base = if (first.length + suffix.length > MAX_TITLE_CHARS) first.take(MAX_TITLE_CHARS - suffix.length).trimEnd() else first
        return base + suffix
    }
}
