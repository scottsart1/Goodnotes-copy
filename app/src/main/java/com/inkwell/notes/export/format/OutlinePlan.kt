package com.inkwell.notes.export.format

import com.inkwell.notes.core.model.OutlineEntry
import com.inkwell.notes.core.model.PageMeta

/** One item of an exported document outline (PDF bookmarks). */
data class OutlineNode(
    val title: String,
    /** Index of the target page in the exported file. */
    val exportIndex: Int,
    val children: List<OutlineNode> = emptyList(),
)

/**
 * Builds the exported outline from the document's outline entries and bookmarked pages.
 * Entries pointing at pages that are not exported are dropped and their children move up
 * to the dropped entry's parent; cycles in parent links are broken.
 */
object OutlinePlan {

    fun build(
        entries: List<OutlineEntry>,
        pages: List<PageMeta>,
        exportedPageIndices: List<Int>,
        pageLabel: (Int) -> String = { "Page ${it + 1}" },
    ): List<OutlineNode> {
        val exportIndexByPageId = HashMap<String, Int>()
        exportedPageIndices.forEachIndexed { exportIndex, pageIndex ->
            pages.getOrNull(pageIndex)?.let { exportIndexByPageId.putIfAbsent(it.id, exportIndex) }
        }
        val byId = entries.associateBy { it.id }
        val children = HashMap<String?, MutableList<OutlineEntry>>()
        for (e in entries) {
            val parent = e.parentId?.takeIf { it in byId && it != e.id && !createsCycle(e, byId) }
            children.getOrPut(parent) { ArrayList() } += e
        }

        fun nodes(parentId: String?, depth: Int): List<OutlineNode> {
            if (depth > MAX_DEPTH) return emptyList()
            val list = children[parentId].orEmpty().sortedWith(compareBy({ it.sortOrder }, { it.title }))
            val out = ArrayList<OutlineNode>()
            for (e in list) {
                val sub = nodes(e.id, depth + 1)
                val target = exportIndexByPageId[e.pageId]
                val title = e.title.trim()
                if (target != null && title.isNotEmpty()) out += OutlineNode(title, target, sub)
                else out += sub // promote children of entries that are not exported
            }
            return out
        }

        val result = nodes(null, 0).toMutableList()
        val outlined = HashSet<Int>()
        fun collect(n: OutlineNode) { outlined += n.exportIndex; n.children.forEach(::collect) }
        result.forEach(::collect)
        exportedPageIndices.forEachIndexed { exportIndex, pageIndex ->
            val page = pages.getOrNull(pageIndex) ?: return@forEachIndexed
            if (!page.bookmarked) return@forEachIndexed
            val title = page.bookmarkTitle?.trim()?.takeIf { it.isNotEmpty() } ?: pageLabel(pageIndex)
            if (exportIndex in outlined && page.bookmarkTitle.isNullOrBlank()) return@forEachIndexed
            result += OutlineNode(title, exportIndex)
        }
        return result
    }

    private fun createsCycle(start: OutlineEntry, byId: Map<String, OutlineEntry>): Boolean {
        val seen = HashSet<String>()
        var cur: OutlineEntry? = start
        while (cur != null) {
            if (!seen.add(cur.id)) return true
            cur = cur.parentId?.let { byId[it] }
        }
        return false
    }

    private const val MAX_DEPTH = 32
}
