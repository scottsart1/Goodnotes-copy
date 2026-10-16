package com.inkwell.notes.ink.tools

import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.LassoFilter
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.geometry.Geo
import com.inkwell.notes.ink.geometry.ItemGeometry
import com.inkwell.notes.ink.model.ContentOps
import com.inkwell.notes.ink.model.ItemIndex
import com.inkwell.notes.ink.model.Items

/** Lasso selection rules. Pure Kotlin. */
object LassoSelector {
    /** An item is selected when at least this fraction of its samples lies inside the loop. */
    const val MOSTLY_INSIDE = 0.6f

    fun accepts(filter: LassoFilter, item: Any): Boolean = when (item) {
        is Stroke -> if (item.pen == PenType.HIGHLIGHTER) filter.highlighter else filter.handwriting
        is ShapeItem, is ConnectorItem -> filter.shapes
        is ImageItem -> filter.images
        is TextItem, is StickyItem -> filter.text
        else -> false
    }

    /**
     * Ids of items mostly inside the closed polygon [poly] (x,y pairs). Groups are expanded so a
     * grouped item never gets selected alone.
     */
    fun select(content: PageContent, index: ItemIndex, poly: FloatArray, filter: LassoFilter): Set<String> {
        val n = poly.size / 2
        if (n < 3 || Geo.polygonArea(poly, n) == 0f) return emptySet()
        val pb = Bounds()
        for (i in 0 until n) pb.union(poly[i * 2], poly[i * 2 + 1])
        val out = LinkedHashSet<String>()
        index.query(pb) { e ->
            val item = e.item
            if (!accepts(filter, item)) return@query
            if (!pb.intersects(e.bounds)) return@query
            val samples = ItemGeometry.samplePoints(item, content)
            if (ItemGeometry.fractionInside(samples, poly, n) >= MOSTLY_INSIDE) out += Items.id(item)
        }
        return expandGroups(content, out)
    }

    /** Adds every member of any group touched by [ids]. */
    fun expandGroups(content: PageContent, ids: Set<String>): Set<String> {
        if (ids.isEmpty()) return ids
        val groups = HashSet<String>()
        val all = ContentOps.allItems(content)
        for (item in all) if (Items.id(item) in ids) Items.groupId(item)?.let { groups += it }
        if (groups.isEmpty()) return ids
        val out = LinkedHashSet(ids)
        for (item in all) if (Items.groupId(item) in groups) out += Items.id(item)
        return out
    }

    /** Rectangle lasso as a polygon. */
    fun rectPolygon(x0: Float, y0: Float, x1: Float, y1: Float): FloatArray =
        floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)
}
