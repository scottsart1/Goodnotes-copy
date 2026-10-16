package com.inkwell.notes.export.format

import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.PaperStyle
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A vector path in page points, independent of android.graphics. */
sealed interface PathOp {
    data class MoveTo(val x: Float, val y: Float) : PathOp
    data class LineTo(val x: Float, val y: Float) : PathOp
    data class CubicTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : PathOp
    data object Close : PathOp
}

fun List<PathOp>.transformed(t: Affine): List<PathOp> = map { op ->
    when (op) {
        is PathOp.MoveTo -> PathOp.MoveTo(t.mapX(op.x, op.y), t.mapY(op.x, op.y))
        is PathOp.LineTo -> PathOp.LineTo(t.mapX(op.x, op.y), t.mapY(op.x, op.y))
        is PathOp.CubicTo -> PathOp.CubicTo(
            t.mapX(op.x1, op.y1), t.mapY(op.x1, op.y1),
            t.mapX(op.x2, op.y2), t.mapY(op.x2, op.y2),
            t.mapX(op.x, op.y), t.mapY(op.x, op.y),
        )
        PathOp.Close -> PathOp.Close
    }
}

/** Geometry of shapes, arrows and rounded boxes, matching the on-screen renderer. */
object ShapeGeometry {
    /** Bezier circle constant. */
    private const val KAPPA = 0.5522848f

    val OPEN_KINDS = setOf(ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.DOUBLE_ARROW)

    /** The outline of [shape]; empty when it has too few points. */
    fun path(shape: ShapeItem): List<PathOp> {
        val p = shape.points
        if (p.size < 4) return emptyList()
        return when (shape.kind) {
            ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.DOUBLE_ARROW ->
                listOf(PathOp.MoveTo(p[0], p[1]), PathOp.LineTo(p[2], p[3]))
            ShapeKind.RECTANGLE, ShapeKind.ROUNDED_RECTANGLE, ShapeKind.ELLIPSE -> {
                val l = min(p[0], p[2]); val r = max(p[0], p[2])
                val t = min(p[1], p[3]); val b = max(p[1], p[3])
                val ops = when (shape.kind) {
                    ShapeKind.RECTANGLE -> rect(l, t, r, b)
                    ShapeKind.ROUNDED_RECTANGLE -> roundRect(l, t, r, b, shape.cornerRadius)
                    else -> ellipse(l, t, r, b)
                }
                if (shape.rotation != 0f) ops.transformed(Affine.rotation(shape.rotation, (l + r) / 2f, (t + b) / 2f)) else ops
            }
            ShapeKind.TRIANGLE, ShapeKind.POLYGON, ShapeKind.STAR -> {
                val ops = ArrayList<PathOp>(p.size / 2 + 1)
                ops += PathOp.MoveTo(p[0], p[1])
                var i = 2
                while (i + 1 < p.size) { ops += PathOp.LineTo(p[i], p[i + 1]); i += 2 }
                ops += PathOp.Close
                ops
            }
        }
    }

    fun rect(l: Float, t: Float, r: Float, b: Float): List<PathOp> = listOf(
        PathOp.MoveTo(l, t), PathOp.LineTo(r, t), PathOp.LineTo(r, b), PathOp.LineTo(l, b), PathOp.Close,
    )

    fun roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float): List<PathOp> {
        val rad = radius.coerceAtLeast(0f).coerceAtMost(min(r - l, b - t) / 2f)
        if (rad <= 0f) return rect(l, t, r, b)
        val k = rad * KAPPA
        return listOf(
            PathOp.MoveTo(l + rad, t),
            PathOp.LineTo(r - rad, t),
            PathOp.CubicTo(r - rad + k, t, r, t + rad - k, r, t + rad),
            PathOp.LineTo(r, b - rad),
            PathOp.CubicTo(r, b - rad + k, r - rad + k, b, r - rad, b),
            PathOp.LineTo(l + rad, b),
            PathOp.CubicTo(l + rad - k, b, l, b - rad + k, l, b - rad),
            PathOp.LineTo(l, t + rad),
            PathOp.CubicTo(l, t + rad - k, l + rad - k, t, l + rad, t),
            PathOp.Close,
        )
    }

    fun ellipse(l: Float, t: Float, r: Float, b: Float): List<PathOp> {
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        val rx = (r - l) / 2f; val ry = (b - t) / 2f
        val kx = rx * KAPPA; val ky = ry * KAPPA
        return listOf(
            PathOp.MoveTo(cx + rx, cy),
            PathOp.CubicTo(cx + rx, cy + ky, cx + kx, cy + ry, cx, cy + ry),
            PathOp.CubicTo(cx - kx, cy + ry, cx - rx, cy + ky, cx - rx, cy),
            PathOp.CubicTo(cx - rx, cy - ky, cx - kx, cy - ry, cx, cy - ry),
            PathOp.CubicTo(cx + kx, cy - ry, cx + rx, cy - ky, cx + rx, cy),
            PathOp.Close,
        )
    }

    fun circle(cx: Float, cy: Float, radius: Float): List<PathOp> = ellipse(cx - radius, cy - radius, cx + radius, cy + radius)

    /** Filled arrow head at (toX, toY) pointing away from (fromX, fromY), as on screen. */
    fun arrowHead(fromX: Float, fromY: Float, toX: Float, toY: Float, width: Float): List<PathOp> {
        val a = atan2(toY - fromY, toX - fromX)
        val len = 6f + width * 3f
        return listOf(
            PathOp.MoveTo(toX, toY),
            PathOp.LineTo(toX - len * cos(a - 0.45f), toY - len * sin(a - 0.45f)),
            PathOp.LineTo(toX - len * cos(a + 0.45f), toY - len * sin(a + 0.45f)),
            PathOp.Close,
        )
    }
}

/** Paper backgrounds as vector lines and dots, matching the on-screen renderer. */
object PaperGeometry {
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float, val color: Int)

    data class Plan(val lines: List<Line>, val dots: FloatArray, val dotRadius: Float, val dotColor: Int)

    /** Colour of the left margin line of ruled paper. */
    const val MARGIN_COLOR: Int = (90 shl 24) or (220 shl 16) or (90 shl 8) or 90

    fun plan(paper: PaperSpec, widthPt: Float, heightPt: Float): Plan {
        val s = paper.effectiveSpacing()
        val lines = ArrayList<Line>()
        var dots = FloatArray(0)
        if (s > 0f && paper.style != PaperStyle.BLANK) {
            when (paper.style) {
                PaperStyle.RULED, PaperStyle.NARROW_RULED, PaperStyle.WIDE_RULED -> {
                    var y = s * 3f
                    while (y < heightPt - s * 0.5f) {
                        lines += Line(0f, y, widthPt, y, 0.6f, paper.lineColor)
                        y += s
                    }
                    lines += Line(s * 3f, 0f, s * 3f, heightPt, 0.6f, MARGIN_COLOR)
                }
                PaperStyle.GRID, PaperStyle.GRAPH -> {
                    var x = 0f
                    var i = 0
                    while (x <= widthPt) {
                        lines += Line(x, 0f, x, heightPt, majorWidth(paper.style, i), paper.lineColor)
                        x += s; i++
                    }
                    var y = 0f
                    i = 0
                    while (y <= heightPt) {
                        lines += Line(0f, y, widthPt, y, majorWidth(paper.style, i), paper.lineColor)
                        y += s; i++
                    }
                }
                PaperStyle.DOT_GRID -> {
                    val list = ArrayList<Float>()
                    var y = s
                    while (y < heightPt) {
                        var x = s
                        while (x < widthPt) { list += x; list += y; x += s }
                        y += s
                    }
                    dots = list.toFloatArray()
                }
                PaperStyle.BLANK -> Unit
            }
        }
        return Plan(lines, dots, 0.9f, paper.lineColor)
    }

    private fun majorWidth(style: PaperStyle, i: Int): Float = if (style == PaperStyle.GRAPH && i % 5 == 0) 0.9f else 0.45f
}
