package com.inkwell.notes.editor.logic

import com.inkwell.notes.core.model.PageMeta

/** Page index, selection and ordering helpers. Indices are 0-based unless stated. */
object PageRanges {

    fun clampIndex(index: Int, count: Int): Int = if (count <= 0) 0 else index.coerceIn(0, count - 1)

    /** 1-based "X" and "N" for "Page X of N". */
    fun displayPosition(currentIndex: Int, count: Int): Pair<Int, Int> =
        if (count <= 0) 0 to 0 else (clampIndex(currentIndex, count) + 1) to count

    /**
     * Parses "1-3, 5, 8–9" (1-based, inclusive) into sorted distinct 0-based indices.
     * Returns null when the text is empty or any part is malformed or out of range.
     */
    fun parse(text: String, pageCount: Int): List<Int>? {
        val parts = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || pageCount <= 0) return null
        val out = sortedSetOf<Int>()
        for (p in parts) {
            val range = p.split('-', '–', '—').map { it.trim() }
            when (range.size) {
                1 -> {
                    val n = range[0].toIntOrNull() ?: return null
                    if (n !in 1..pageCount) return null
                    out += n - 1
                }
                2 -> {
                    val a = range[0].toIntOrNull() ?: return null
                    val b = range[1].toIntOrNull() ?: return null
                    if (a !in 1..pageCount || b !in 1..pageCount || a > b) return null
                    for (n in a..b) out += n - 1
                }
                else -> return null
            }
        }
        return out.toList()
    }

    /** Formats 0-based indices as compact 1-based ranges: [0,1,2,4] -> "1–3, 5". */
    fun format(indices: Collection<Int>): String {
        val sorted = indices.distinct().sorted()
        if (sorted.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var start = sorted[0]
        var prev = start
        fun close() { parts += if (start == prev) "${start + 1}" else "${start + 1}–${prev + 1}" }
        for (i in 1 until sorted.size) {
            val n = sorted[i]
            if (n == prev + 1) { prev = n; continue }
            close()
            start = n; prev = n
        }
        close()
        return parts.joinToString(", ")
    }

    /** Index at which a page inserted before/after [anchor] lands. */
    fun insertionIndex(anchor: Int, after: Boolean, count: Int): Int {
        if (count <= 0) return 0
        val a = clampIndex(anchor, count)
        return if (after) a + 1 else a
    }

    /** Moves the element at [from] to [to] (indices in the original list). */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val m = list.toMutableList()
        val item = m.removeAt(from)
        m.add(to, item)
        return m
    }

    fun toggle(selected: Set<String>, id: String): Set<String> = if (id in selected) selected - id else selected + id

    /** Selected pages in document order. */
    fun orderedSelection(pages: List<PageMeta>, selectedIds: Set<String>): List<PageMeta> = pages.filter { it.id in selectedIds }

    fun indicesOf(pages: List<PageMeta>, selectedIds: Set<String>): List<Int> =
        pages.mapIndexedNotNull { i, p -> if (p.id in selectedIds) i else null }

    /** A document keeps at least one page. */
    fun canDelete(selectedCount: Int, totalCount: Int): Boolean = selectedCount in 1 until totalCount

    /** Where the current page index should go after the pages in [deletedIndices] are removed. */
    fun indexAfterDelete(current: Int, deletedIndices: Collection<Int>, countBefore: Int): Int {
        val remaining = countBefore - deletedIndices.distinct().size
        if (remaining <= 0) return 0
        // Pages before the current one shift it up; if the current page itself goes, its
        // successor takes its place (or the new last page).
        val shift = deletedIndices.distinct().count { it < current }
        return clampIndex(current - shift, remaining)
    }

    /** True when [ids] is a reordering of [pages] that differs from their current order. */
    fun isReorder(pages: List<PageMeta>, ids: List<String>): Boolean =
        ids.size == pages.size && ids.toSet() == pages.map { it.id }.toSet() && ids != pages.map { it.id }
}
