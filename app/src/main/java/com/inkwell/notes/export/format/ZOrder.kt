package com.inkwell.notes.export.format

import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import kotlin.math.max
import kotlin.math.min

/**
 * Page items in paint order, using the same rule as the on-screen renderer: one z order
 * for all item types, highlighter strokes beneath ink of the same z, images, stickies and
 * connectors beneath strokes, shapes and text of the same z.
 */
object ZOrder {
    fun ordered(content: PageContent): List<Any> {
        val entries = ArrayList<Pair<Int, Any>>(
            content.strokes.size + content.shapes.size + content.texts.size + content.images.size +
                content.stickies.size + content.connectors.size,
        )
        content.strokes.forEach { entries += (it.z * 2 + if (it.pen == PenType.HIGHLIGHTER) 0 else 1) to it }
        content.shapes.forEach { entries += (it.z * 2 + 1) to it }
        content.texts.forEach { entries += (it.z * 2 + 1) to it }
        content.images.forEach { entries += (it.z * 2) to it }
        content.stickies.forEach { entries += (it.z * 2) to it }
        content.connectors.forEach { entries += (it.z * 2) to it }
        return entries.sortedBy { it.first }.map { it.second }
    }

    /** True when the page has anything besides typed text (Word adds a picture of such pages). */
    fun hasInk(content: PageContent): Boolean =
        content.strokes.isNotEmpty() || content.shapes.isNotEmpty() || content.images.isNotEmpty() || content.connectors.isNotEmpty()

    /**
     * Bounds (left, top, right, bottom) of all items in page points, or null when empty.
     * Unlike the renderer's quick bounds this includes stroke widths, rotated shapes and
     * connectors, so whiteboard exports never clip.
     */
    fun contentBounds(content: PageContent): FloatArray? {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var any = false
        fun add(x0: Float, y0: Float, x1: Float, y1: Float) {
            if (x0.isNaN() || y0.isNaN() || x1.isNaN() || y1.isNaN()) return
            l = min(l, x0); t = min(t, y0); r = max(r, x1); b = max(b, y1); any = true
        }
        for (s in content.strokes) {
            val pts = s.points
            val half = s.width
            var i = 0
            while (i + 1 < pts.size) {
                add(pts[i] - half, pts[i + 1] - half, pts[i] + half, pts[i + 1] + half)
                i += InkPoints.STRIDE
            }
        }
        for (s in content.shapes) {
            val ops = ShapeGeometry.path(s)
            val pad = s.strokeWidth / 2f + if (s.kind in ShapeGeometry.OPEN_KINDS) 6f + s.strokeWidth * 3f else 0f
            for (op in ops) when (op) {
                is PathOp.MoveTo -> add(op.x - pad, op.y - pad, op.x + pad, op.y + pad)
                is PathOp.LineTo -> add(op.x - pad, op.y - pad, op.x + pad, op.y + pad)
                is PathOp.CubicTo -> add(op.x - pad, op.y - pad, op.x + pad, op.y + pad)
                PathOp.Close -> Unit
            }
        }
        for (t2 in content.texts) rotatedBox(t2.x, t2.y, t2.width, t2.height, t2.rotation, ::add)
        for (i2 in content.images) rotatedBox(i2.x, i2.y, i2.width, i2.height, i2.rotation, ::add)
        for (s in content.stickies) add(s.x, s.y, s.x + s.width + 3f, s.y + s.height + 3f)
        for (c in content.connectors) {
            val pad = c.width + 6f + c.width * 3f
            add(min(c.fromX, c.toX) - pad, min(c.fromY, c.toY) - pad, max(c.fromX, c.toX) + pad, max(c.fromY, c.toY) + pad)
        }
        return if (any) floatArrayOf(l, t, r, b) else null
    }

    private inline fun rotatedBox(x: Float, y: Float, w: Float, h: Float, rotation: Float, add: (Float, Float, Float, Float) -> Unit) {
        if (rotation == 0f) {
            add(x, y, x + w, y + h)
            return
        }
        val pts = Affine.rotation(rotation, x + w / 2f, y + h / 2f).mapPoints(floatArrayOf(x, y, x + w, y, x + w, y + h, x, y + h))
        val bb = Affine.boundsOf(pts)
        add(bb[0], bb[1], bb[2], bb[3])
    }
}
