package com.inkwell.notes.ink.geometry

import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.ink.render.PenModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Bounds, outlines and hit tests of page items in page points. Pure Kotlin. */
object ItemGeometry {
    private const val ELLIPSE_SEGMENTS = 32

    /** Conservative bounds of a stroke including its maximum drawn width. */
    fun strokeBounds(s: Stroke, out: Bounds): Bounds {
        Geo.polylineBounds(s.points, InkPoints.STRIDE, out)
        return out.outset(s.width * PenModel.maxFactor(s.pen) * 0.5f + 0.5f)
    }

    /** Corners (x,y * 4) of a box rotated by [rotation] degrees around its centre. */
    fun boxCorners(x: Float, y: Float, w: Float, h: Float, rotation: Float, out: FloatArray = FloatArray(8)): FloatArray {
        out[0] = x; out[1] = y; out[2] = x + w; out[3] = y; out[4] = x + w; out[5] = y + h; out[6] = x; out[7] = y + h
        if (rotation != 0f) rotateAround(out, 4, x + w / 2f, y + h / 2f, rotation)
        return out
    }

    fun rotateAround(pts: FloatArray, n: Int, cx: Float, cy: Float, degrees: Float) {
        val a = degrees * PI.toFloat() / 180f
        val c = cos(a); val s = sin(a)
        for (i in 0 until n) {
            val dx = pts[i * 2] - cx; val dy = pts[i * 2 + 1] - cy
            pts[i * 2] = cx + dx * c - dy * s
            pts[i * 2 + 1] = cy + dx * s + dy * c
        }
    }

    /** Whether a shape kind encloses an area (and can be filled). */
    fun isClosed(kind: ShapeKind): Boolean = kind != ShapeKind.LINE && kind != ShapeKind.ARROW && kind != ShapeKind.DOUBLE_ARROW

    /**
     * Polyline approximation of a shape's outline as x,y pairs. Closed kinds repeat the first
     * vertex at the end so the outline can be walked as segments.
     */
    fun shapeOutline(shape: ShapeItem): FloatArray {
        val p = shape.points
        if (p.size < 4) return if (p.size >= 2) floatArrayOf(p[0], p[1]) else FloatArray(0)
        return when (shape.kind) {
            ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.DOUBLE_ARROW -> floatArrayOf(p[0], p[1], p[2], p[3])
            ShapeKind.RECTANGLE, ShapeKind.ROUNDED_RECTANGLE -> {
                val l = min(p[0], p[2]); val t = min(p[1], p[3])
                val c = boxCorners(l, t, abs(p[2] - p[0]), abs(p[3] - p[1]), shape.rotation)
                floatArrayOf(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[0], c[1])
            }
            ShapeKind.ELLIPSE -> {
                val l = min(p[0], p[2]); val t = min(p[1], p[3])
                val rx = abs(p[2] - p[0]) / 2f; val ry = abs(p[3] - p[1]) / 2f
                val cx = l + rx; val cy = t + ry
                val out = FloatArray((ELLIPSE_SEGMENTS + 1) * 2)
                for (i in 0..ELLIPSE_SEGMENTS) {
                    val a = 2f * PI.toFloat() * i / ELLIPSE_SEGMENTS
                    out[i * 2] = cx + rx * cos(a); out[i * 2 + 1] = cy + ry * sin(a)
                }
                if (shape.rotation != 0f) rotateAround(out, ELLIPSE_SEGMENTS + 1, cx, cy, shape.rotation)
                out
            }
            ShapeKind.TRIANGLE, ShapeKind.POLYGON, ShapeKind.STAR -> {
                val out = p.copyOf(p.size - p.size % 2 + 2)
                out[out.size - 2] = p[0]; out[out.size - 1] = p[1]
                out
            }
        }
    }

    /** Bounds of any page item; connectors resolve attached endpoints through [content]. */
    fun bounds(item: Any, out: Bounds, content: PageContent? = null): Bounds = when (item) {
        is Stroke -> strokeBounds(item, out)
        is ShapeItem -> {
            val o = shapeOutline(item)
            Geo.polylineBounds(o, 2, out)
            var pad = item.strokeWidth / 2f + 0.5f
            if (item.kind == ShapeKind.ARROW || item.kind == ShapeKind.DOUBLE_ARROW) pad += 6f + item.strokeWidth * 3f
            out.outset(pad)
        }
        is TextItem -> boxBounds(item.x, item.y, item.width, item.height, item.rotation, out)
        is ImageItem -> boxBounds(item.x, item.y, item.width, item.height, item.rotation, out)
        is StickyItem -> out.set(item.x, item.y, item.x + item.width, item.y + item.height).outset(3f)
        is ConnectorItem -> {
            val e = FloatArray(4)
            connectorEndpoints(item, content, e)
            out.setPoints(e[0], e[1], e[2], e[3]).outset(item.width / 2f + 6f + item.width * 3f)
        }
        else -> out.setEmpty()
    }

    fun boxBounds(x: Float, y: Float, w: Float, h: Float, rotation: Float, out: Bounds): Bounds {
        if (rotation == 0f) return out.set(x, y, x + w, y + h)
        val c = boxCorners(x, y, w, h, rotation)
        out.setEmpty()
        for (i in 0 until 4) out.union(c[i * 2], c[i * 2 + 1])
        return out
    }

    /** Unrotated attachment rectangle of an item, as connectors use it. */
    fun attachBounds(content: PageContent, id: String, out: Bounds): Bounds? {
        content.stickies.firstOrNull { it.id == id }?.let { return out.set(it.x, it.y, it.x + it.width, it.y + it.height) }
        content.texts.firstOrNull { it.id == id }?.let { return out.set(it.x, it.y, it.x + it.width, it.y + it.height) }
        content.images.firstOrNull { it.id == id }?.let { return out.set(it.x, it.y, it.x + it.width, it.y + it.height) }
        content.shapes.firstOrNull { it.id == id }?.let { return Geo.polylineBounds(it.points, 2, out) }
        content.strokes.firstOrNull { it.id == id }?.let { return Geo.polylineBounds(it.points, InkPoints.STRIDE, out) }
        return null
    }

    /** Live endpoints of a connector (nearest edge midpoints of attached items) into [out] (fx, fy, tx, ty). */
    fun connectorEndpoints(c: ConnectorItem, content: PageContent?, out: FloatArray): FloatArray {
        val fromB = if (content != null && c.fromItemId != null) attachBounds(content, c.fromItemId, Bounds()) else null
        val toB = if (content != null && c.toItemId != null) attachBounds(content, c.toItemId, Bounds()) else null
        var fx = c.fromX; var fy = c.fromY; var tx = c.toX; var ty = c.toY
        if (fromB != null) {
            val towardX = toB?.centerX ?: tx; val towardY = toB?.centerY ?: ty
            edgePoint(fromB, towardX, towardY, out); fx = out[0]; fy = out[1]
        }
        if (toB != null) {
            val towardX = fromB?.centerX ?: fx; val towardY = fromB?.centerY ?: fy
            edgePoint(toB, towardX, towardY, out); tx = out[0]; ty = out[1]
        }
        out[0] = fx; out[1] = fy; out[2] = tx; out[3] = ty
        return out
    }

    private fun edgePoint(r: Bounds, towardX: Float, towardY: Float, out: FloatArray) {
        val dx = towardX - r.centerX; val dy = towardY - r.centerY
        if (abs(dx) * r.height > abs(dy) * r.width) {
            out[0] = if (dx > 0) r.right else r.left; out[1] = r.centerY
        } else {
            out[0] = r.centerX; out[1] = if (dy > 0) r.bottom else r.top
        }
    }

    /** Maps a point into the unrotated frame of a box rotated around its centre. */
    private fun unrotate(x: Float, y: Float, bx: Float, by: Float, bw: Float, bh: Float, rotation: Float, out: FloatArray) {
        if (rotation == 0f) { out[0] = x; out[1] = y; return }
        val cx = bx + bw / 2f; val cy = by + bh / 2f
        val a = -rotation * PI.toFloat() / 180f
        val c = cos(a); val s = sin(a)
        val dx = x - cx; val dy = y - cy
        out[0] = cx + dx * c - dy * s; out[1] = cy + dx * s + dy * c
    }

    private fun inBox(x: Float, y: Float, bx: Float, by: Float, bw: Float, bh: Float, rotation: Float, tol: Float): Boolean {
        val p = FloatArray(2)
        unrotate(x, y, bx, by, bw, bh, rotation, p)
        return p[0] >= bx - tol && p[0] <= bx + bw + tol && p[1] >= by - tol && p[1] <= by + bh + tol
    }

    /** Whether a tap at (x, y) with tolerance [tol] hits [item]. */
    fun hitPoint(item: Any, x: Float, y: Float, tol: Float, content: PageContent? = null): Boolean = when (item) {
        is Stroke -> {
            val r = tol + item.width * 0.6f
            Geo.distSqPointPolyline(x, y, item.points, InkPoints.STRIDE) <= r * r
        }
        is ShapeItem -> {
            val o = shapeOutline(item)
            val r = tol + item.strokeWidth / 2f
            (Geo.distSqPointPolyline(x, y, o, 2) <= r * r) ||
                (isClosed(item.kind) && item.fillColor != 0 && Geo.pointInPolygon(x, y, o, o.size / 2 - 1))
        }
        is TextItem -> inBox(x, y, item.x, item.y, item.width, item.height, item.rotation, tol)
        is ImageItem -> inBox(x, y, item.x, item.y, item.width, item.height, item.rotation, tol)
        is StickyItem -> inBox(x, y, item.x, item.y, item.width, item.height, 0f, tol)
        is ConnectorItem -> {
            val e = connectorEndpoints(item, content, FloatArray(4))
            val r = tol + item.width / 2f
            Geo.distSqPointSegment(x, y, e[0], e[1], e[2], e[3]) <= r * r
        }
        else -> false
    }

    /**
     * Whether the swept eraser capsule (segment A-B with radius [r]) touches [item]'s visible
     * geometry. Filled closed shapes are hit anywhere inside.
     */
    fun hitSegment(item: Any, ax: Float, ay: Float, bx: Float, by: Float, r: Float, content: PageContent? = null): Boolean = when (item) {
        is Stroke -> {
            val rr = r + item.width * 0.5f
            Geo.distSqSegmentPolyline(ax, ay, bx, by, item.points, InkPoints.STRIDE) <= rr * rr
        }
        is ShapeItem -> {
            val o = shapeOutline(item)
            val rr = r + item.strokeWidth / 2f
            Geo.distSqSegmentPolyline(ax, ay, bx, by, o, 2) <= rr * rr ||
                (isClosed(item.kind) && item.fillColor != 0 && (Geo.pointInPolygon(ax, ay, o, o.size / 2 - 1) || Geo.pointInPolygon(bx, by, o, o.size / 2 - 1)))
        }
        is ConnectorItem -> {
            val e = connectorEndpoints(item, content, FloatArray(4))
            val rr = r + item.width / 2f
            Geo.segmentDistanceSq(ax, ay, bx, by, e[0], e[1], e[2], e[3]) <= rr * rr
        }
        is TextItem, is ImageItem, is StickyItem -> {
            val b = bounds(item, Bounds(), content)
            val expanded = b.copy().outset(r)
            Geo.segmentIntersectsRect(ax, ay, bx, by, expanded)
        }
        else -> false
    }

    /** Arc-length position (page points from the start) of the point of [s] nearest to (x, y). */
    fun nearestArcLength(s: Stroke, x: Float, y: Float): Float {
        val p = s.points
        val n = s.pointCount
        if (n < 2) return 0f
        val st = InkPoints.STRIDE
        var best = Float.POSITIVE_INFINITY
        var bestS = 0f
        var acc = 0f
        for (i in 1 until n) {
            val ax = p[(i - 1) * st]; val ay = p[(i - 1) * st + 1]; val bx = p[i * st]; val by = p[i * st + 1]
            val t = Geo.closestParam(x, y, ax, ay, bx, by)
            val segLen = Geo.dist(ax, ay, bx, by)
            val d = Geo.distSq(x, y, ax + (bx - ax) * t, ay + (by - ay) * t)
            if (d < best) { best = d; bestS = acc + segLen * t }
            acc += segLen
        }
        return bestS
    }

    /**
     * Sample points of an item for "mostly inside" lasso tests, as x,y pairs. Strokes are sampled
     * evenly along their arc length (not by sample index), so a stroke written slowly at one end
     * and quickly at the other is still judged by how much of its *ink* lies inside.
     */
    fun samplePoints(item: Any, content: PageContent? = null, maxSamples: Int = 48): FloatArray = when (item) {
        is Stroke -> strokeSamples(item, maxSamples)
        is ShapeItem -> shapeOutline(item)
        is TextItem -> boxSamples(item.x, item.y, item.width, item.height, item.rotation)
        is ImageItem -> boxSamples(item.x, item.y, item.width, item.height, item.rotation)
        is StickyItem -> boxSamples(item.x, item.y, item.width, item.height, 0f)
        is ConnectorItem -> {
            val e = connectorEndpoints(item, content, FloatArray(4))
            floatArrayOf(e[0], e[1], (e[0] + e[2]) / 2f, (e[1] + e[3]) / 2f, e[2], e[3])
        }
        else -> FloatArray(0)
    }

    /**
     * Up to [maxSamples] points of [s] evenly spaced along its arc length (both ends included).
     * A stroke without extent (one sample, or every sample at the same spot) gives one point.
     */
    fun strokeSamples(s: Stroke, maxSamples: Int = 48): FloatArray {
        val st = InkPoints.STRIDE
        val p = s.points
        val n = s.pointCount
        if (n == 0) return FloatArray(0)
        val total = Geo.polylineLength(p, st, n)
        if (n == 1 || !(total > 0f) || !total.isFinite()) return floatArrayOf(p[0], p[1])
        val count = min(max(2, maxSamples), n)
        val out = FloatArray(count * 2)
        out[0] = p[0]; out[1] = p[1]
        val step = total / (count - 1)
        var k = 1
        var acc = 0f
        var target = step
        for (i in 1 until n) {
            val ax = p[(i - 1) * st]; val ay = p[(i - 1) * st + 1]
            val bx = p[i * st]; val by = p[i * st + 1]
            val seg = Geo.dist(ax, ay, bx, by)
            while (seg > 0f && acc + seg >= target && k < count - 1) {
                val t = (target - acc) / seg
                out[k * 2] = ax + (bx - ax) * t; out[k * 2 + 1] = ay + (by - ay) * t
                k++
                target += step
            }
            acc += seg
        }
        // Rounding slack: the remaining slots take the end point.
        while (k < count) { out[k * 2] = p[(n - 1) * st]; out[k * 2 + 1] = p[(n - 1) * st + 1]; k++ }
        return out
    }

    private fun boxSamples(x: Float, y: Float, w: Float, h: Float, rotation: Float): FloatArray {
        val out = FloatArray(18)
        var k = 0
        for (fy in 0..2) for (fx in 0..2) { out[k++] = x + w * fx / 2f; out[k++] = y + h * fy / 2f }
        if (rotation != 0f) rotateAround(out, 9, x + w / 2f, y + h / 2f, rotation)
        return out
    }

    /** Fraction (0..1) of [samples] (x,y pairs) inside polygon [poly] (x,y pairs, [n] vertices). */
    fun fractionInside(samples: FloatArray, poly: FloatArray, n: Int = poly.size / 2): Float {
        val count = samples.size / 2
        if (count == 0) return 0f
        var inside = 0
        for (i in 0 until count) if (Geo.pointInPolygon(samples[i * 2], samples[i * 2 + 1], poly, n)) inside++
        return inside.toFloat() / count
    }

    /** Height of a stroke's bounds without its width padding. */
    fun strokeInkHeight(s: Stroke): Float {
        val b = Geo.polylineBounds(s.points, InkPoints.STRIDE, Bounds())
        return b.height
    }

    fun length(ax: Float, ay: Float): Float = sqrt(ax * ax + ay * ay)
}
