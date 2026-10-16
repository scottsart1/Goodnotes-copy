package com.inkwell.notes.ink.recognize

import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.geometry.ItemGeometry
import com.inkwell.notes.ink.model.ItemIndex
import com.inkwell.notes.ink.model.Items
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Scribble-to-erase: a fast zig-zag drawn over existing ink deletes what it covers. A stroke is a
 * scribble only when it swings back and forth at least [MIN_REVERSALS] times with large swings,
 * nearly all of its length is spent swinging (high reversal density: sharp zig-zags, not the
 * arches and loops of letters like m, w or cursive words), it was drawn quickly, and the ink under
 * it fills a real part of its area. Pure Kotlin.
 */
object ScribbleDetector {
    const val MIN_REVERSALS = 4
    /** Reversals x swing amplitude / path length: about 0.85 for zig-zags, about 0.6 for letters. */
    const val MIN_DENSITY = 0.7f
    /** Each swing must cover this fraction of the extent along its axis. */
    const val SWING = 0.4f
    /** Minimum share of the scribble's area that covered ink must fill. */
    const val MIN_COVERAGE = 0.15f
    /** Items need this fraction of their samples inside the scribble's hull. */
    const val ITEM_INSIDE = 0.5f

    class Analysis(
        val reversals: Int,
        /** reversals x swing extent / path length. */
        val density: Float,
        /** Path length / bounding diagonal. */
        val lengthRatio: Float,
        /** Mean speed in page points per millisecond (0 when timing is unknown). */
        val speed: Float,
        val bounds: Bounds,
    ) {
        override fun toString() = "Analysis(reversals=$reversals, density=$density, lengthRatio=$lengthRatio, speed=$speed)"
    }

    /** Swing statistics along one axis. */
    class Swings(val reversals: Int, val extent: Float)

    /** Shape statistics of a stroke ([xy] page points, [times] ms per sample or null). */
    fun analyze(xy: FloatArray, times: FloatArray?): Analysis {
        val b = PathOps.bounds(xy)
        val n = PathOps.count(xy)
        val len = PathOps.length(xy)
        val diag = hypot(b.width, b.height)
        if (n < 6 || diag <= 0f || len <= 0f) return Analysis(0, 0f, 0f, 0f, b)
        // Resampling evens out sampling rate and removes sub-pixel jitter.
        val r = PathOps.resample(xy, max(16, min(400, (len / (diag / 40f)).toInt())))
        val pa = PathOps.principalAxis(r)
        val along = swings(r, pa[2], pa[3])
        val across = swings(r, -pa[3], pa[2])
        val best = if (across.reversals > along.reversals) across else along
        val duration = if (times != null && times.size >= n) times[n - 1] - times[0] else 0f
        val speed = if (duration > 0f) len / duration else 0f
        return Analysis(best.reversals, best.reversals * best.extent / len, len / diag, speed, b)
    }

    /** Direction reversals of the projection onto (ux, uy), with hysteresis of [SWING] x extent. */
    fun swings(xy: FloatArray, ux: Float, uy: Float): Swings {
        val n = PathOps.count(xy)
        if (n < 3) return Swings(0, 0f)
        var lo = Float.POSITIVE_INFINITY; var hi = Float.NEGATIVE_INFINITY
        val proj = FloatArray(n)
        for (i in 0 until n) { proj[i] = xy[i * 2] * ux + xy[i * 2 + 1] * uy; lo = min(lo, proj[i]); hi = max(hi, proj[i]) }
        val h = (hi - lo) * SWING
        if (h <= 0f) return Swings(0, 0f)
        var dir = 0
        var extreme = proj[0]
        var count = 0
        for (i in 1 until n) {
            val v = proj[i]
            when (dir) {
                0 -> if (v - proj[0] >= h) { dir = 1; extreme = v } else if (proj[0] - v >= h) { dir = -1; extreme = v }
                1 -> if (v > extreme) extreme = v else if (extreme - v >= h) { dir = -1; extreme = v; count++ }
                else -> if (v < extreme) extreme = v else if (v - extreme >= h) { dir = 1; extreme = v; count++ }
            }
        }
        return Swings(count, hi - lo)
    }

    /**
     * True when the stroke has the shape of a scribble. [minSpeed] (page points per ms, 0 to skip)
     * rejects slow, deliberate drawing.
     */
    fun looksLikeScribble(a: Analysis, minSpeed: Float): Boolean =
        a.reversals >= MIN_REVERSALS && a.density >= MIN_DENSITY && a.lengthRatio >= 2f &&
            (minSpeed <= 0f || a.speed <= 0f || a.speed >= minSpeed)

    /**
     * Ids of the ink (handwriting, shapes, connectors; never highlighting, text, images or stickies) the scribble
     * [xy] covers, or an empty set when it does not cover enough ink to be an erase gesture.
     */
    fun coveredIds(content: PageContent, index: ItemIndex, xy: FloatArray, scribbleWidth: Float): Set<String> {
        val hull = PathOps.convexHull(xy)
        val hn = hull.size / 2
        if (hn < 3) return emptySet()
        val hb = PathOps.bounds(hull)
        val hullArea = PathOps.polygonArea(hull)
        if (hullArea <= 0f) return emptySet()
        val out = LinkedHashSet<String>()
        var covered = 0f
        val tmp = Bounds()
        index.query(hb) { e ->
            val item = e.item
            // Highlighting is never scribbled away (people write over highlights).
            if (!((item is Stroke && item.pen != PenType.HIGHLIGHTER) || item is ShapeItem || item is ConnectorItem)) return@query
            val samples = ItemGeometry.samplePoints(item, content, 64)
            if (samples.isEmpty()) return@query
            if (ItemGeometry.fractionInside(samples, hull, hn) < ITEM_INSIDE) return@query
            if (!touches(item, xy, scribbleWidth, content)) return@query
            out += Items.id(item)
            val ib = ItemGeometry.bounds(item, tmp, content).copy().intersect(hb)
            covered += ib.width * ib.height
        }
        if (out.isEmpty()) return emptySet()
        val coverage = covered / hb.width.coerceAtLeast(1e-3f) / hb.height.coerceAtLeast(1e-3f)
        return if (coverage >= MIN_COVERAGE) out else emptySet()
    }

    /** Whether the scribble path passes over (or right next to) the item's ink. */
    private fun touches(item: Any, xy: FloatArray, width: Float, content: PageContent): Boolean {
        val n = PathOps.count(xy)
        val reach = max(width, 2f)
        for (i in 1 until n) {
            if (ItemGeometry.hitSegment(item, xy[(i - 1) * 2], xy[(i - 1) * 2 + 1], xy[i * 2], xy[i * 2 + 1], reach, content)) return true
        }
        return false
    }
}
