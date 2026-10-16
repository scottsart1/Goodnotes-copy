package com.inkwell.notes.ink.select

import com.inkwell.notes.ink.geometry.Bounds
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt

/** Parts of the selection box a gesture can grab. */
enum class Handle(
    /** -1 = left edge, 0 = centre, 1 = right edge of the box. */
    val fx: Int,
    /** -1 = top edge, 0 = centre, 1 = bottom edge. */
    val fy: Int,
) {
    BODY(0, 0),
    TOP_LEFT(-1, -1), TOP(0, -1), TOP_RIGHT(1, -1), RIGHT(1, 0),
    BOTTOM_RIGHT(1, 1), BOTTOM(0, 1), BOTTOM_LEFT(-1, 1), LEFT(-1, 0),
    ROTATE(0, -1);

    val isCorner: Boolean get() = fx != 0 && fy != 0 && this != ROTATE
    val isEdge: Boolean get() = (fx == 0) != (fy == 0) && this != ROTATE
    val isResize: Boolean get() = isCorner || isEdge

    companion object {
        val CORNERS = listOf(TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT)
        val EDGES = listOf(TOP, RIGHT, BOTTOM, LEFT)
    }
}

/**
 * Transform math of direct selection manipulation, in page points. Corners scale uniformly
 * around the opposite corner, edges scale one axis around the opposite edge, the rotate handle
 * turns around the box centre and snaps to right angles. Pure Kotlin.
 */
object SelectionMath {
    /** Rotation snaps to multiples of 90 degrees within this many degrees. */
    const val SNAP_DEGREES = 4f

    /** Page point of a handle on bounds [b] (corners and edge midpoints; BODY/ROTATE = centre). */
    fun handlePoint(h: Handle, b: Bounds, out: FloatArray) {
        out[0] = when (h.fx) { -1 -> b.left; 1 -> b.right; else -> b.centerX }
        out[1] = when (h.fy) { -1 -> b.top; 1 -> b.bottom; else -> b.centerY }
        if (h == Handle.BODY || h == Handle.ROTATE) { out[0] = b.centerX; out[1] = b.centerY }
    }

    /**
     * Scale transform for dragging resize handle [h] of bounds [b] from page point ([x0], [y0]) to
     * ([x], [y]). The grabbed handle follows the pointer (the initial grab offset is kept).
     * Sizes never drop below [minSize] and the box never flips.
     */
    fun scaleTransform(h: Handle, b: Bounds, x0: Float, y0: Float, x: Float, y: Float, minSize: Float): Affine {
        if (!h.isResize || b.isEmpty) return Affine.IDENTITY
        val hp = FloatArray(2)
        handlePoint(h, b, hp)
        // Anchor: the opposite corner or edge.
        val ax = when (h.fx) { -1 -> b.right; 1 -> b.left; else -> b.centerX }
        val ay = when (h.fy) { -1 -> b.bottom; 1 -> b.top; else -> b.centerY }
        // Virtual handle position: the pointer minus its offset from the handle at the grab.
        val vx = x - (x0 - hp[0]); val vy = y - (y0 - hp[1])
        val w = max(b.width, 1e-3f); val hgt = max(b.height, 1e-3f)
        val minSx = (minSize / w).coerceAtMost(1f); val minSy = (minSize / hgt).coerceAtMost(1f)
        if (h.isCorner) {
            val dx = hp[0] - ax; val dy = hp[1] - ay
            val len2 = dx * dx + dy * dy
            if (len2 < 1e-9f) return Affine.IDENTITY
            val s = (((vx - ax) * dx + (vy - ay) * dy) / len2).coerceAtLeast(max(minSx, minSy))
            return Affine.scale(s, s, ax, ay)
        }
        // A box without extent along the dragged axis has nothing to scale (and would divide by zero).
        return if (h.fx != 0) {
            val span = hp[0] - ax
            if (abs(span) < 1e-6f) return Affine.IDENTITY
            val s = ((vx - ax) / span).coerceAtLeast(minSx)
            Affine.scale(s, 1f, ax, ay)
        } else {
            val span = hp[1] - ay
            if (abs(span) < 1e-6f) return Affine.IDENTITY
            val s = ((vy - ay) / span).coerceAtLeast(minSy)
            Affine.scale(1f, s, ax, ay)
        }
    }

    /**
     * Rotation in degrees for a pointer moving from ([x0], [y0]) to ([x], [y]) around ([cx], [cy]).
     * The resulting absolute angle ([baseDegrees] + rotation) snaps to 0/90/180/270 within
     * [SNAP_DEGREES].
     */
    fun rotationDegrees(cx: Float, cy: Float, x0: Float, y0: Float, x: Float, y: Float, baseDegrees: Float = 0f): Float {
        val a0 = atan2(y0 - cy, x0 - cx); val a1 = atan2(y - cy, x - cx)
        var deg = (a1 - a0) * 180f / PI.toFloat()
        deg = TransformBaker.normalizeDegrees(deg)
        return snapRotation(baseDegrees + deg) - baseDegrees
    }

    /** Snaps an absolute angle to the nearest right angle when within [SNAP_DEGREES]. */
    fun snapRotation(degrees: Float): Float {
        val nearest = (degrees / 90f).roundToInt() * 90f
        return if (abs(degrees - nearest) <= SNAP_DEGREES) nearest else degrees
    }

    /** Snaps the direction of a line from (x0, y0) to (x1, y1) to [stepDegrees] steps; returns the end point. */
    fun snapLineAngle(x0: Float, y0: Float, x1: Float, y1: Float, stepDegrees: Float = 15f): FloatArray {
        val dx = x1 - x0; val dy = y1 - y0
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len < 1e-6f) return floatArrayOf(x1, y1)
        val a = atan2(dy, dx) * 180f / PI.toFloat()
        val snapped = (a / stepDegrees).roundToInt() * stepDegrees * PI.toFloat() / 180f
        return floatArrayOf(x0 + len * kotlin.math.cos(snapped), y0 + len * kotlin.math.sin(snapped))
    }

    /** Bounds of [b] mapped through [m] (axis aligned around the transformed corners). */
    fun transformedBounds(b: Bounds, m: Affine, out: Bounds = Bounds()): Bounds {
        out.setEmpty()
        if (b.isEmpty) return out
        for (k in 0 until 4) {
            val x = if (k == 0 || k == 3) b.left else b.right
            val y = if (k < 2) b.top else b.bottom
            out.union(m.mapX(x, y), m.mapY(x, y))
        }
        return out
    }
}

/**
 * Where the handles of a selection box sit on screen and which one a pointer grabs. Works in view
 * pixels on the box's (padded) view rectangle. Pure Kotlin.
 */
class SelectionHandles {
    /** Box in view pixels. */
    val box = Bounds()
    var showEdges = true
    var showHorizontalEdges = true
    var showVerticalEdges = true
    var showRotate = true
    /** Distance of the rotate handle above the box (px). */
    var rotateOffset = 28f
    /** Touch radius of a handle (px): 22dp gives the 44dp minimum target. */
    var touchRadius = 44f

    val rotateX: Float get() = box.centerX
    val rotateY: Float get() = box.top - rotateOffset

    fun position(h: Handle, out: FloatArray) {
        if (h == Handle.ROTATE) { out[0] = rotateX; out[1] = rotateY; return }
        out[0] = when (h.fx) { -1 -> box.left; 1 -> box.right; else -> box.centerX }
        out[1] = when (h.fy) { -1 -> box.top; 1 -> box.bottom; else -> box.centerY }
    }

    fun isVisible(h: Handle): Boolean = when {
        h == Handle.BODY -> true
        h == Handle.ROTATE -> showRotate
        h.isCorner -> true
        // LEFT/RIGHT change the width (horizontal), TOP/BOTTOM the height.
        h.fx != 0 -> showEdges && showHorizontalEdges
        else -> showEdges && showVerticalEdges
    }

    /** The handle at view point ([x], [y]), BODY inside the box, or null outside. */
    fun hit(x: Float, y: Float): Handle? {
        if (box.isEmpty) return null
        val r = touchRadius
        val p = FloatArray(2)
        if (showRotate) {
            position(Handle.ROTATE, p)
            if (dist2(x, y, p[0], p[1]) <= r * r) return Handle.ROTATE
        }
        var best: Handle? = null
        var bestD = Float.POSITIVE_INFINITY
        for (h in Handle.CORNERS + Handle.EDGES) {
            if (!isVisible(h)) continue
            position(h, p)
            val d = dist2(x, y, p[0], p[1])
            if (d <= r * r && d < bestD) { best = h; bestD = d }
        }
        val w = box.width; val hgt = box.height
        if (best != null) {
            // On small boxes the handle targets cover the middle: well inside means "move".
            val small = w < 3f * r || hgt < 3f * r
            if (small) {
                val ix = w * 0.25f; val iy = hgt * 0.25f
                if (x > box.left + ix && x < box.right - ix && y > box.top + iy && y < box.bottom - iy) return Handle.BODY
            }
            return best
        }
        val slack = r * 0.25f
        return if (x >= box.left - slack && x <= box.right + slack && y >= box.top - slack && y <= box.bottom + slack) Handle.BODY else null
    }

    private fun dist2(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx; val dy = ay - by
        return dx * dx + dy * dy
    }
}
