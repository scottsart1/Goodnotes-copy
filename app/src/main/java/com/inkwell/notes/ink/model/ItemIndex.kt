package com.inkwell.notes.ink.model

import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.geometry.ItemGeometry
import com.inkwell.notes.ink.geometry.SpatialGrid
import com.inkwell.notes.ink.render.RenderOrder
import java.util.IdentityHashMap

/**
 * Spatial index of one immutable [PageContent] snapshot: item bounds, paint order and a grid for
 * hit testing, erasing and lasso lookups. Built lazily per content version; item bounds are
 * cached by item identity across versions so rebuilding after a small edit is cheap.
 */
class ItemIndex private constructor(
    val content: PageContent,
    val entries: List<Entry>,
    private val grid: SpatialGrid,
) {
    class Entry(val item: Any, val bounds: Bounds, /** Paint order: higher is drawn later (on top). */ val rank: Int)

    fun query(q: Bounds, fn: (Entry) -> Unit) {
        grid.query(q) { fn(entries[it]) }
    }

    /** Entries intersecting [q] sorted by paint order (bottom first). */
    fun queryOrdered(q: Bounds): List<Entry> {
        val out = ArrayList<Entry>()
        query(q) { out += it }
        out.sortBy { it.rank }
        return out
    }

    /** Topmost item hit by a tap at (x, y) that satisfies [accept]. */
    fun hitTest(x: Float, y: Float, tol: Float, accept: (Any) -> Boolean = { true }): Any? {
        val q = Bounds(x - tol, y - tol, x + tol, y + tol)
        var best: Entry? = null
        query(q) { e ->
            if ((best == null || e.rank > best!!.rank) && accept(e.item) && ItemGeometry.hitPoint(e.item, x, y, tol, content)) best = e
        }
        return best?.item
    }

    /** Items touched by the swept eraser capsule A-B with radius [r]. */
    fun hitBySegment(ax: Float, ay: Float, bx: Float, by: Float, r: Float, accept: (Any) -> Boolean): List<Any> {
        val q = Bounds().setPoints(ax, ay, bx, by).outset(r)
        val out = ArrayList<Any>()
        query(q) { e -> if (accept(e.item) && ItemGeometry.hitSegment(e.item, ax, ay, bx, by, r, content)) out += e.item }
        return out
    }

    fun boundsOf(item: Any): Bounds? = entries.firstOrNull { it.item === item }?.bounds

    companion object {
        /**
         * Builds the index. [cache] maps item instances to bounds and is updated; pass the same
         * map across versions of one page.
         */
        fun build(content: PageContent, cache: IdentityHashMap<Any, Bounds>): ItemIndex {
            val ordered = RenderOrder.order(content)
            val grid = SpatialGrid()
            val entries = ArrayList<Entry>(ordered.size)
            val live = IdentityHashMap<Any, Bounds>(ordered.size * 2)
            ordered.forEachIndexed { rank, item ->
                // Connector bounds depend on other items, so never reuse them.
                val b = (if (item is com.inkwell.notes.core.model.ConnectorItem) null else cache[item])
                    ?: ItemGeometry.bounds(item, Bounds(), content)
                live[item] = b
                entries += Entry(item, b, rank)
                grid.insert(rank, b)
            }
            cache.clear()
            cache.putAll(live)
            return ItemIndex(content, entries, grid)
        }
    }
}
