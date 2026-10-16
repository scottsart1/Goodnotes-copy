package com.inkwell.notes.ink.geometry

import kotlin.math.floor

/**
 * Uniform grid over item bounds for fast spatial lookups on large pages and unbounded
 * whiteboards. Entries are integer handles chosen by the caller. Items spanning very many
 * cells are kept in an "oversized" list that every query checks.
 */
class SpatialGrid(private val cellSize: Float = 96f) {
    private val cells = HashMap<Long, IntList>()
    private val oversized = IntList()
    private val entryBounds = ArrayList<Bounds?>()

    val size: Int get() = entryBounds.count { it != null }

    fun clear() {
        cells.clear(); oversized.clear(); entryBounds.clear()
    }

    /** Cell index of a coordinate; saturates (Float.toInt) for absurd values instead of wrapping. */
    private fun cell(v: Float): Int = floor(v / cellSize).toInt()
    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

    /**
     * Number of cells in the index range. Computed in 64 bits, with each axis capped first, so a
     * range spanning the whole Int domain never overflows to a small (or zero) count and gets
     * walked cell by cell.
     */
    private fun span(x0: Int, x1: Int, y0: Int, y1: Int): Long {
        val dx = x1.toLong() - x0 + 1L; val dy = y1.toLong() - y0 + 1L
        if (dx > AXIS_CAP || dy > AXIS_CAP) return Long.MAX_VALUE
        return dx * dy
    }

    fun insert(handle: Int, b: Bounds) {
        while (entryBounds.size <= handle) entryBounds.add(null)
        entryBounds[handle] = b.copy()
        if (b.isEmpty) return
        val x0 = cell(b.left); val x1 = cell(b.right); val y0 = cell(b.top); val y1 = cell(b.bottom)
        val span = span(x0, x1, y0, y1)
        if (span > MAX_CELLS_PER_ITEM) { oversized.add(handle); return }
        for (cy in y0..y1) for (cx in x0..x1) cells.getOrPut(key(cx, cy)) { IntList() }.add(handle)
    }

    /** Calls [fn] once for every handle whose bounds intersect [q]. */
    fun query(q: Bounds, fn: (Int) -> Unit) {
        if (q.isEmpty) return
        val seen = HashSet<Int>()
        val x0 = cell(q.left); val x1 = cell(q.right); val y0 = cell(q.top); val y1 = cell(q.bottom)
        val span = span(x0, x1, y0, y1)
        if (span > MAX_QUERY_CELLS) {
            // Very large query (zoomed far out): scan all entries.
            for (h in entryBounds.indices) { val b = entryBounds[h] ?: continue; if (b.intersects(q)) fn(h) }
            return
        }
        for (cy in y0..y1) for (cx in x0..x1) {
            val list = cells[key(cx, cy)] ?: continue
            for (i in 0 until list.size) {
                val h = list[i]
                if (seen.add(h)) { val b = entryBounds[h]; if (b != null && b.intersects(q)) fn(h) }
            }
        }
        for (i in 0 until oversized.size) {
            val h = oversized[i]
            if (seen.add(h)) { val b = entryBounds[h]; if (b != null && b.intersects(q)) fn(h) }
        }
    }

    fun bounds(handle: Int): Bounds? = entryBounds.getOrNull(handle)

    companion object {
        const val MAX_CELLS_PER_ITEM = 400L
        const val MAX_QUERY_CELLS = 4096L
        private const val AXIS_CAP = 1L shl 30
    }
}

/** Growable int buffer. */
class IntList(initialCapacity: Int = 8) {
    private var data = IntArray(initialCapacity.coerceAtLeast(2))
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Int = data[i]
    fun clear() { size = 0 }
}
