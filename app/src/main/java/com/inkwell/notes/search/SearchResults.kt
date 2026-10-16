package com.inkwell.notes.search

import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.SearchHit
import com.inkwell.notes.core.model.TextSource
import java.util.Locale

/** Result filter chips. */
enum class SearchFilter(val sources: Set<TextSource>?) {
    ALL(null),
    HANDWRITING(setOf(TextSource.HANDWRITING)),
    TYPED(setOf(TextSource.TYPED)),
    PDF_OCR(setOf(TextSource.PDF_TEXT, TextSource.OCR)),
    TRANSCRIPTS(setOf(TextSource.TRANSCRIPT)),
    TITLES(setOf(TextSource.TITLE, TextSource.TAG, TextSource.FOLDER));

    fun accepts(source: TextSource): Boolean = sources == null || source in sources
}

/** All matches in one document, in rank order. */
data class DocGroup(
    val documentId: String,
    val title: String,
    val kind: DocKind,
    val folderPath: String,
    val thumbnailPath: String?,
    /** Every accepted hit of the document, including a title match. */
    val hits: List<SearchHit>,
) {
    /** Hits shown as rows; a title match is shown by highlighting the header instead. */
    val rows: List<SearchHit> get() = hits.filter { it.source != TextSource.TITLE }
    val titleMatched: Boolean get() = hits.any { it.source == TextSource.TITLE }

    /** Page to open from the header: the best hit's page, if any. */
    val firstPageIndex: Int? get() = rows.firstOrNull { it.pageIndex != null }?.pageIndex
}

/** Plain helpers for presenting search results. */
object SearchResults {
    /** Groups [hits] by document, keeping rank order of documents (best hit first) and of hits. */
    fun group(hits: List<SearchHit>, filter: SearchFilter): List<DocGroup> {
        val byDoc = LinkedHashMap<String, MutableList<SearchHit>>()
        for (h in hits) {
            if (!filter.accepts(h.source)) continue
            byDoc.getOrPut(h.documentId) { ArrayList() } += h
        }
        return byDoc.map { (id, list) ->
            val first = list.first()
            DocGroup(id, first.documentTitle, first.kind, first.folderPath, first.thumbnailPath, list)
        }
    }

    /** Number of hits per filter (ALL = every hit). */
    fun counts(hits: List<SearchHit>): Map<SearchFilter, Int> =
        SearchFilter.entries.associateWith { f -> hits.count { f.accepts(it.source) } }

    /** "p.3" for pages, "18:40" for recordings, null otherwise. */
    fun location(hit: SearchHit): String? = when {
        hit.timestampMs != null && (hit.recordingId != null || hit.source == TextSource.TRANSCRIPT) -> formatTimestamp(hit.timestampMs)
        hit.pageIndex != null -> "p.${hit.pageIndex + 1}"
        else -> null
    }

    /** m:ss below an hour, h:mm:ss above. */
    fun formatTimestamp(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** Whether a small page preview makes sense for this hit. */
    fun hasPagePreview(hit: SearchHit): Boolean =
        hit.pageId != null && hit.pageId != TYPED_PAGE && hit.pageIndex != null && hit.kind != DocKind.TYPED

    private const val TYPED_PAGE = "typed"
}

/** Most-recent-first list of recent queries, without duplicates (case-insensitive). */
object RecentQueries {
    const val MAX = 10
    private const val MAX_LENGTH = 200

    fun add(list: List<String>, query: String, max: Int = MAX): List<String> {
        val q = query.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH)
        if (q.isEmpty()) return list
        return (listOf(q) + list.filterNot { it.equals(q, ignoreCase = true) }).take(max)
    }

    fun remove(list: List<String>, query: String): List<String> = list.filterNot { it.equals(query.trim(), ignoreCase = true) }
}
