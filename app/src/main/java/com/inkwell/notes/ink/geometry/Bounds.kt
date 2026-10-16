package com.inkwell.notes.ink.geometry

import kotlin.math.max
import kotlin.math.min

/**
 * Mutable axis-aligned rectangle in plain Kotlin. The ink engine's geometry uses this instead of
 * android.graphics.RectF so it can be unit tested on the JVM (RectF is stubbed there).
 *
 * An empty bounds has left > right; [union] with an empty bounds is a no-op.
 */
class Bounds(
    @JvmField var left: Float = Float.POSITIVE_INFINITY,
    @JvmField var top: Float = Float.POSITIVE_INFINITY,
    @JvmField var right: Float = Float.NEGATIVE_INFINITY,
    @JvmField var bottom: Float = Float.NEGATIVE_INFINITY,
) {
    val isEmpty: Boolean get() = left > right || top > bottom
    val width: Float get() = if (isEmpty) 0f else right - left
    val height: Float get() = if (isEmpty) 0f else bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    fun set(l: Float, t: Float, r: Float, b: Float): Bounds {
        left = l; top = t; right = r; bottom = b
        return this
    }

    fun set(o: Bounds): Bounds = set(o.left, o.top, o.right, o.bottom)

    fun setEmpty(): Bounds = set(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)

    /** Sets this to the bounds of two points (in any order). */
    fun setPoints(x0: Float, y0: Float, x1: Float, y1: Float): Bounds = set(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))

    fun union(x: Float, y: Float): Bounds {
        if (x < left) left = x
        if (x > right) right = x
        if (y < top) top = y
        if (y > bottom) bottom = y
        return this
    }

    fun union(o: Bounds): Bounds {
        if (o.isEmpty) return this
        if (isEmpty) return set(o)
        left = min(left, o.left); top = min(top, o.top); right = max(right, o.right); bottom = max(bottom, o.bottom)
        return this
    }

    fun union(l: Float, t: Float, r: Float, b: Float): Bounds {
        if (l > r || t > b) return this
        if (isEmpty) return set(l, t, r, b)
        left = min(left, l); top = min(top, t); right = max(right, r); bottom = max(bottom, b)
        return this
    }

    /** Grows the bounds by [d] on every side (shrinks for negative values). */
    fun outset(d: Float): Bounds {
        if (isEmpty) return this
        left -= d; top -= d; right += d; bottom += d
        return this
    }

    fun offset(dx: Float, dy: Float): Bounds {
        if (isEmpty) return this
        left += dx; right += dx; top += dy; bottom += dy
        return this
    }

    fun contains(x: Float, y: Float): Boolean = !isEmpty && x >= left && x <= right && y >= top && y <= bottom

    fun contains(o: Bounds): Boolean = !isEmpty && !o.isEmpty && o.left >= left && o.right <= right && o.top >= top && o.bottom <= bottom

    fun intersects(o: Bounds): Boolean =
        !isEmpty && !o.isEmpty && o.left <= right && o.right >= left && o.top <= bottom && o.bottom >= top

    fun intersects(l: Float, t: Float, r: Float, b: Float): Boolean =
        !isEmpty && l <= right && r >= left && t <= bottom && b >= top

    /** Intersects this with [o]; becomes empty when they do not overlap. */
    fun intersect(o: Bounds): Bounds {
        if (!intersects(o)) return setEmpty()
        left = max(left, o.left); top = max(top, o.top); right = min(right, o.right); bottom = min(bottom, o.bottom)
        return this
    }

    fun copy(): Bounds = Bounds(left, top, right, bottom)

    override fun equals(other: Any?): Boolean =
        other is Bounds && ((isEmpty && other.isEmpty) || (other.left == left && other.top == top && other.right == right && other.bottom == bottom))

    override fun hashCode(): Int = if (isEmpty) 0 else ((left.hashCode() * 31 + top.hashCode()) * 31 + right.hashCode()) * 31 + bottom.hashCode()

    override fun toString(): String = if (isEmpty) "Bounds(empty)" else "Bounds($left, $top, $right, $bottom)"

    companion object {
        fun of(l: Float, t: Float, r: Float, b: Float) = Bounds(l, t, r, b)
    }
}
