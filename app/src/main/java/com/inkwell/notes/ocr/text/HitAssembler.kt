package com.inkwell.notes.ocr.text

import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.SearchHit
import com.inkwell.notes.core.model.TextSource

/** A possible search result before ranking. [key] identifies duplicates across match paths. */
data class Candidate(
    val key: String,
    val documentId: String,
    val pageId: String?,
    val pageIndex: Int?,
    val recordingId: String?,
    val timestampMs: Long?,
    val source: TextSource,
    val text: String,
    val confidence: Float? = null,
    val bounds: String? = null,
    val semantic: Float? = null,
) {
    companion object {
        fun keyForRow(rowId: Long, documentId: String, source: TextSource, text: String): String = when (source) {
            TextSource.TITLE -> titleKey(documentId)
            TextSource.TAG -> tagKey(documentId, text)
            else -> "row:$rowId"
        }

        fun titleKey(documentId: String) = "title:$documentId"
        fun tagKey(documentId: String, tag: String) = "tag:$documentId:${tag.trim().lowercase()}"
        fun folderKey(documentId: String) = "folder:$documentId"
    }
}

/** The document facts a result needs. */
data class DocInfo(
    val id: String,
    val title: String,
    val kind: DocKind,
    val folderId: String?,
    val modifiedAt: Long,
    val thumbnailPath: String?,
    val tags: List<String> = emptyList(),
)

data class Ranked(val candidate: Candidate, val score: Double)

/** Pure assembly of search results: metadata matching, merging, ranking and hit building. */
object HitAssembler {
    /** Folder-name matches can include whole folders; keep the most recent ones. */
    const val MAX_FOLDER_MATCHES = 40

    /**
     * Substring matches on titles, tags and folder names (the full-text index only matches word
     * starts): a field matches when it contains the whole query or every query word.
     */
    fun metaMatches(docs: Collection<DocInfo>, folders: Map<String, FolderPaths.Node>, terms: List<String>, rawQuery: String): List<Candidate> {
        val needle = rawQuery.trim().lowercase()
        if (needle.isEmpty() && terms.isEmpty()) return emptyList()
        fun matches(s: String): Boolean {
            val l = s.lowercase()
            return (needle.isNotEmpty() && l.contains(needle)) || (terms.isNotEmpty() && terms.all { l.contains(it) })
        }
        val out = ArrayList<Candidate>()
        val folderHits = ArrayList<Pair<DocInfo, String>>()
        val folderMatch = HashMap<String, Boolean>()
        for (d in docs) {
            if (matches(d.title) || matches(d.title.replace('_', ' '))) {
                out += Candidate(Candidate.titleKey(d.id), d.id, null, null, null, null, TextSource.TITLE, d.title)
            }
            for (tag in d.tags) {
                if (tag.isNotBlank() && matches(tag)) out += Candidate(Candidate.tagKey(d.id, tag), d.id, null, null, null, null, TextSource.TAG, tag.trim())
            }
            val fid = d.folderId ?: continue
            val hit = folderMatch.getOrPut(fid) {
                val chain = FolderPaths.chain(folders, fid)
                chain.any { matches(it.name) } || (chain.isNotEmpty() && matches(chain.joinToString(" ") { it.name }))
            }
            if (hit) folderHits += d to FolderPaths.path(folders, fid)
        }
        folderHits.sortedByDescending { it.first.modifiedAt }.take(MAX_FOLDER_MATCHES).forEach { (d, path) ->
            out += Candidate(Candidate.folderKey(d.id), d.id, null, null, null, null, TextSource.FOLDER, path)
        }
        return out
    }

    /** Keyword and semantic candidates merged by key; a candidate found both ways keeps its similarity. */
    fun merge(keyword: List<Candidate>, semantic: List<Candidate>): List<Candidate> =
        Ranking.merge(keyword, semantic.map { it to (it.semantic ?: 0f) }, { it.key }) { c, sim -> c.copy(semantic = maxOf(sim, c.semantic ?: 0f)) }

    /**
     * Scores [candidates], drops non-matches and duplicates (keeping the best), and orders by
     * score, then document recency, then position in the document.
     */
    fun rank(candidates: List<Candidate>, docs: Map<String, DocInfo>, terms: List<String>, now: Long, limit: Int): List<Ranked> {
        val best = LinkedHashMap<String, Ranked>()
        for (c in candidates) {
            val doc = docs[c.documentId] ?: continue
            val score = Ranking.score(RankInput(c.text, c.source, doc.modifiedAt, c.confidence, c.semantic), terms, now)
            if (score <= 0.0) continue
            val prev = best[c.key]
            if (prev == null || score > prev.score) best[c.key] = Ranked(c, score)
        }
        return best.values.sortedWith(
            compareByDescending<Ranked> { it.score }
                .thenByDescending { docs[it.candidate.documentId]?.modifiedAt ?: 0L }
                .thenBy { it.candidate.documentId }
                .thenBy { it.candidate.pageIndex ?: Int.MAX_VALUE }
                .thenBy { it.candidate.timestampMs ?: Long.MAX_VALUE },
        ).take(limit.coerceAtLeast(0))
    }

    fun toHit(r: Ranked, doc: DocInfo, folderPath: String, terms: List<String>): SearchHit {
        val c = r.candidate
        val excerpt = Excerpts.build(c.text, terms)
        return SearchHit(
            documentId = doc.id,
            documentTitle = doc.title,
            kind = doc.kind,
            folderPath = folderPath,
            pageId = c.pageId,
            pageIndex = c.pageIndex,
            recordingId = c.recordingId,
            timestampMs = c.timestampMs,
            source = c.source,
            excerpt = excerpt.text,
            matchStart = excerpt.matchStart,
            matchEnd = excerpt.matchEnd,
            score = r.score,
            thumbnailPath = doc.thumbnailPath,
            bounds = Bounds.parse(c.bounds),
        )
    }
}
