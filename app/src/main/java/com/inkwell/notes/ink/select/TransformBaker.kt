package com.inkwell.notes.ink.select

import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Bakes an [Affine] transform into page items so a move, resize or rotation becomes plain item
 * data: stroke samples move (widths scale with the mean scale), shapes keep their kind (box kinds
 * keep a rotation field), text boxes, images and stickies move their box (text scales its font on
 * uniform scaling, otherwise re-wraps in the new width), connectors move their free ends.
 * Attached connector ends follow their items automatically. Pure Kotlin.
 */
object TransformBaker {
    private const val MIN_SIZE = 0.5f

    fun bake(item: Any, m: Affine): Any {
        if (m.isIdentity) return item
        return when (item) {
            is Stroke -> stroke(item, m)
            is ShapeItem -> shape(item, m)
            is TextItem -> text(item, m)
            is ImageItem -> image(item, m)
            is StickyItem -> sticky(item, m)
            is ConnectorItem -> connector(item, m)
            else -> item
        }
    }

    fun stroke(s: Stroke, m: Affine): Stroke {
        val p = s.points.copyOf()
        m.mapPoints(p, InkPoints.STRIDE)
        val w = if (m.isTranslation) s.width else (s.width * m.meanScale).coerceIn(0.1f, 200f)
        return s.copy(points = p, width = w)
    }

    fun shape(sh: ShapeItem, m: Affine): ShapeItem {
        val k = m.meanScale
        val width = if (m.isTranslation) sh.strokeWidth else (sh.strokeWidth * k).coerceIn(0.1f, 200f)
        return when (sh.kind) {
            ShapeKind.RECTANGLE, ShapeKind.ROUNDED_RECTANGLE, ShapeKind.ELLIPSE -> {
                val p = sh.points
                if (p.size < 4) return sh.copy(strokeWidth = width)
                val l = min(p[0], p[2]); val t = min(p[1], p[3])
                val box = box(l, t, abs(p[2] - p[0]), abs(p[3] - p[1]), sh.rotation, m)
                val cx = box[0]; val cy = box[1]; val w = box[2]; val h = box[3]
                sh.copy(
                    points = floatArrayOf(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f),
                    rotation = box[4], strokeWidth = width,
                    cornerRadius = if (m.isTranslation) sh.cornerRadius else sh.cornerRadius * k,
                )
            }
            else -> {
                val p = sh.points.copyOf()
                m.mapPoints(p, 2)
                sh.copy(points = p, strokeWidth = width)
            }
        }
    }

    fun text(t: TextItem, m: Affine): TextItem {
        val box = box(t.x, t.y, t.width, t.height, t.rotation, m)
        val sx = box[5]; val sy = box[6]
        val uniform = abs(sx - sy) <= 0.01f * max(sx, sy)
        val font = if (uniform) (t.fontSizePt * sx).coerceIn(2f, 400f) else t.fontSizePt
        return t.copy(
            x = box[0] - box[2] / 2f, y = box[1] - box[3] / 2f, width = box[2], height = box[3],
            rotation = box[4], fontSizePt = font,
        )
    }

    fun image(i: ImageItem, m: Affine): ImageItem {
        val box = box(i.x, i.y, i.width, i.height, i.rotation, m)
        return i.copy(x = box[0] - box[2] / 2f, y = box[1] - box[3] / 2f, width = box[2], height = box[3], rotation = box[4])
    }

    /** Stickies have no rotation: the box moves with its centre and keeps standing upright. */
    fun sticky(s: StickyItem, m: Affine): StickyItem {
        val cx = s.x + s.width / 2f; val cy = s.y + s.height / 2f
        val ncx = m.mapX(cx, cy); val ncy = m.mapY(cx, cy)
        val w = max(MIN_SIZE, s.width * length(m.a, m.b))
        val h = max(MIN_SIZE, s.height * length(m.c, m.d))
        return s.copy(x = ncx - w / 2f, y = ncy - h / 2f, width = w, height = h)
    }

    fun connector(c: ConnectorItem, m: Affine): ConnectorItem = c.copy(
        fromX = m.mapX(c.fromX, c.fromY), fromY = m.mapY(c.fromX, c.fromY),
        toX = m.mapX(c.toX, c.toY), toY = m.mapY(c.toX, c.toY),
        width = if (m.isTranslation) c.width else (c.width * m.meanScale).coerceIn(0.1f, 60f),
    )

    /**
     * Transforms a box (top-left [x],[y], size, rotation in degrees around its centre) by [m].
     * Returns [cx, cy, w, h, rotationDeg, scaleAlongWidth, scaleAlongHeight]. Exact for moves,
     * rotations, uniform scales and axis-aligned scales of unrotated boxes; shear is dropped.
     */
    fun box(x: Float, y: Float, w: Float, h: Float, rotationDeg: Float, m: Affine): FloatArray {
        val cx = x + w / 2f; val cy = y + h / 2f
        val ncx = m.mapX(cx, cy); val ncy = m.mapY(cx, cy)
        val r = rotationDeg * PI.toFloat() / 180f
        val ux = cos(r); val uy = sin(r)
        val vx = -uy; val vy = ux
        // Local axes after the linear part of the transform.
        val ux2 = m.a * ux + m.c * uy; val uy2 = m.b * ux + m.d * uy
        val vx2 = m.a * vx + m.c * vy; val vy2 = m.b * vx + m.d * vy
        val su = length(ux2, uy2); val sv = length(vx2, vy2)
        val rot = normalizeDegrees(if (su > 1e-6f) atan2(uy2, ux2) * 180f / PI.toFloat() else rotationDeg)
        return floatArrayOf(ncx, ncy, max(MIN_SIZE, w * su), max(MIN_SIZE, h * sv), rot, su, sv)
    }

    /** Degrees in (-180, 180]; values within 0.01 degrees of zero become exactly zero. */
    fun normalizeDegrees(deg: Float): Float {
        var r = deg % 360f
        if (r <= -180f) r += 360f
        if (r > 180f) r -= 360f
        return if (abs(r) < 0.01f) 0f else r
    }

    private fun length(x: Float, y: Float): Float = sqrt(x * x + y * y)
}
