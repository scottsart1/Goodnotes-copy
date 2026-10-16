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
import com.inkwell.notes.ink.recognize.PathOps

/**
 * Lasso selection rules: strokes, shapes and connectors are selected when most of their samples
 * lie inside the loop; text boxes, images and stickies when their centre does. Pure Kotlin.
 */
object LassoSelector {
    /** An item is selected when at least this fraction of its samples lies inside the loop. */
    const val MOSTLY_INSIDE = 0.6f

    /** Whether [item] counts as inside the closed polygon [poly] ([n] vertices). */
    fun isInside(item: Any, content: PageContent, poly: FloatArray, n: Int = poly.size / 2): Boolean = when (item) {
        is TextItem -> Geo.pointInPolygon(item.x + item.width / 2f, item.y + item.height / 2f, poly, n)
        is ImageItem -> Geo.pointInPolygon(item.x + item.width / 2f, item.y + item.height / 2f, poly, n)
        is StickyItem -> Geo.pointInPolygon(item.x + item.width / 2f, item.y + item.height / 2f, poly, n)
        is ShapeItem -> ItemGeometry.fractionInside(outlineSamples(item), poly, n) >= MOSTLY_INSIDE
        else -> ItemGeometry.fractionInside(ItemGeometry.samplePoints(item, content), poly, n) >= MOSTLY_INSIDE
    }

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
        if (n < 3) return emptySet()
        val pb = Bounds()
        for (i in 0 until n) pb.union(poly[i * 2], poly[i * 2 + 1])
        // A loop without extent encloses nothing. (The signed area is not a usable test here: the
        // lobes of a self-intersecting loop cancel out even though each of them encloses items.)
        if (pb.isEmpty || pb.width <= 0f || pb.height <= 0f) return emptySet()
        val out = LinkedHashSet<String>()
        index.query(pb) { e ->
            val item = e.item
            if (!accepts(filter, item)) return@query
            if (!pb.intersects(e.bounds)) return@query
            if (isInside(item, content, poly, n)) out += Items.id(item)
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

    /** Evenly spaced outline samples (a rectangle's outline alone is just its corners). */
    private fun outlineSamples(shape: ShapeItem): FloatArray {
        val o = ItemGeometry.shapeOutline(shape)
        return if (o.size < 4) o else PathOps.resample(o, 32)
    }

    /** Ids of items mostly inside [poly] without group expansion (used by gestures). */
    fun inside(content: PageContent, index: ItemIndex, poly: FloatArray, filter: LassoFilter): Set<String> {
        val n = poly.size / 2
        if (n < 3) return emptySet()
        val pb = Bounds()
        for (i in 0 until n) pb.union(poly[i * 2], poly[i * 2 + 1])
        val out = LinkedHashSet<String>()
        index.query(pb) { e -> if (accepts(filter, e.item) && isInside(e.item, content, poly, n)) out += Items.id(e.item) }
        return out
    }

    /** Rectangle lasso as a polygon. */
    fun rectPolygon(x0: Float, y0: Float, x1: Float, y1: Float): FloatArray =
        floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)
}

