package com.inkwell.notes.ink.tools

import com.inkwell.notes.core.model.ShapeKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Builds ShapeItem point arrays from a drag: lines and arrows run from start to end, boxes span
 * corner to corner, and triangle / polygon / star vertices are inscribed in the dragged box.
 * Pure Kotlin.
 */
object ShapeBuilder {
    fun points(kind: ShapeKind, x0: Float, y0: Float, x1: Float, y1: Float): FloatArray {
        val l = min(x0, x1); val t = min(y0, y1); val r = max(x0, x1); val b = max(y0, y1)
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        val rx = (r - l) / 2f; val ry = (b - t) / 2f
        return when (kind) {
            ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.DOUBLE_ARROW -> floatArrayOf(x0, y0, x1, y1)
            ShapeKind.RECTANGLE, ShapeKind.ROUNDED_RECTANGLE, ShapeKind.ELLIPSE -> floatArrayOf(l, t, r, b)
            ShapeKind.TRIANGLE -> floatArrayOf(cx, t, r, b, l, b)
            ShapeKind.POLYGON -> regular(cx, cy, rx, ry, 6, -PI / 2)
            ShapeKind.STAR -> star(cx, cy, rx, ry, 5)
        }
    }

    /** True when the drag is too small to be a deliberate shape. */
    fun isTooSmall(kind: ShapeKind, x0: Float, y0: Float, x1: Float, y1: Float, minSize: Float): Boolean {
        val w = abs(x1 - x0); val h = abs(y1 - y0)
        return when (kind) {
            ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.DOUBLE_ARROW -> w * w + h * h < minSize * minSize
            else -> w < minSize && h < minSize
        }
    }

    private fun regular(cx: Float, cy: Float, rx: Float, ry: Float, n: Int, start: Double): FloatArray {
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val a = start + 2.0 * PI * i / n
            out[i * 2] = cx + rx * cos(a).toFloat(); out[i * 2 + 1] = cy + ry * sin(a).toFloat()
        }
        return out
    }

    private fun star(cx: Float, cy: Float, rx: Float, ry: Float, points: Int): FloatArray {
        val out = FloatArray(points * 4)
        val inner = 0.45f
        for (i in 0 until points * 2) {
            val a = -PI / 2 + PI * i / points
            val k = if (i % 2 == 0) 1f else inner
            out[i * 2] = cx + rx * k * cos(a).toFloat(); out[i * 2 + 1] = cy + ry * k * sin(a).toFloat()
        }
        return out
    }
}
