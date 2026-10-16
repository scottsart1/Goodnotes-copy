package com.inkwell.notes.ink.select

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import com.inkwell.notes.R
import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.ink.engine.InkEngine
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.render.PageRenderer
import kotlin.math.roundToInt

/**
 * Draws the selection chrome in view pixels: a thin accent box with round corner handles, pill
 * edge handles and a rotate handle; while dragging, the lifted items through the live transform,
 * the transformed outline, alignment guides, the destination page and the rotation angle.
 */
internal class SelectionPainter(private val e: InkEngine) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val matrix = Matrix()
    private val itemMatrix = Matrix()
    private val values = FloatArray(9)
    private val path = Path()
    private val rect = RectF()
    private val pos = FloatArray(2)
    private val corner = FloatArray(2)
    private var typeface: Typeface? = null
    private var typefaceLoaded = false

    private fun dp(v: Float) = v * e.density

    private fun font(): Typeface? {
        if (!typefaceLoaded) {
            typefaceLoaded = true
            typeface = try { e.context.resources.getFont(R.font.figtree_medium) } catch (t: Throwable) { null }
        }
        return typeface
    }

    // ------------------------------------------------------------------ Static box

    fun drawBox(canvas: Canvas, h: SelectionHandles, active: Handle?) {
        val t = e.theme
        val b = h.box
        rect.set(b.left, b.top, b.right, b.bottom)
        fillPaint.color = t.withAlpha(t.selection, 0x10)
        canvas.drawRect(rect, fillPaint)
        linePaint.color = t.selection
        linePaint.strokeWidth = dp(1.25f)
        linePaint.pathEffect = null
        canvas.drawRect(rect, linePaint)
        handleFill.color = t.surface
        handleRing.color = t.selection
        handleRing.strokeWidth = dp(1.5f)
        if (h.showRotate) {
            h.position(Handle.ROTATE, pos)
            val r = dp(10f)
            linePaint.strokeWidth = dp(1f)
            canvas.drawLine(b.centerX, b.top, pos[0], pos[1] + r, linePaint)
            fillPaint.color = t.selection
            canvas.drawCircle(pos[0], pos[1], if (active == Handle.ROTATE) r * 1.15f else r, fillPaint)
            drawRotateGlyph(canvas, pos[0], pos[1], dp(4.5f), t.onAccent)
        }
        for (handle in Handle.EDGES) {
            if (!h.isVisible(handle)) continue
            h.position(handle, pos)
            val long = dp(7f); val short = dp(2.75f)
            val horizontal = handle.fy != 0
            val hw = if (horizontal) long else short; val hh = if (horizontal) short else long
            rect.set(pos[0] - hw, pos[1] - hh, pos[0] + hw, pos[1] + hh)
            canvas.drawRoundRect(rect, short, short, handleFill)
            canvas.drawRoundRect(rect, short, short, handleRing)
        }
        for (handle in Handle.CORNERS) {
            h.position(handle, pos)
            val r = if (active == handle) dp(6.5f) else dp(5.5f)
            canvas.drawCircle(pos[0], pos[1], r, handleFill)
            canvas.drawCircle(pos[0], pos[1], r, handleRing)
        }
    }

    /** A circular arrow (about 300 degrees with an arrowhead). */
    private fun drawRotateGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        glyphPaint.color = color
        glyphPaint.strokeWidth = dp(1.4f)
        rect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, -60f, 290f, false, glyphPaint)
        // Arrowhead at the arc's start (pointing clockwise).
        val a = Math.toRadians(-60.0)
        val ex = cx + r * Math.cos(a).toFloat(); val ey = cy + r * Math.sin(a).toFloat()
        val s = dp(2.2f)
        path.reset()
        path.moveTo(ex - s, ey - s * 0.4f)
        path.lineTo(ex, ey)
        path.lineTo(ex - s * 0.2f, ey + s)
        canvas.drawPath(path, glyphPaint)
    }

    // ------------------------------------------------------------------ Dragging

    fun drawLifted(
        canvas: Canvas, pageIndex: Int, t: Affine, strokes: List<Pair<Stroke, Path>>,
        others: List<Any>, attached: List<ConnectorItem>, context: PageContent,
    ) {
        canvas.save()
        e.pageMatrix(pageIndex, matrix)
        canvas.concat(matrix)
        if (strokes.isNotEmpty()) {
            canvas.save()
            itemMatrix.setValues(t.toMatrixValues(values))
            canvas.concat(itemMatrix)
            for ((s, p) in strokes) PageRenderer.drawStrokePath(canvas, s, p, false)
            canvas.restore()
        }
        for (item in others) {
            when (item) {
                is ShapeItem -> PageRenderer.drawShape(canvas, item)
                is TextItem -> PageRenderer.drawText(canvas, item)
                is ImageItem -> PageRenderer.drawImage(canvas, item) { e.render.images.get(it) }
                is StickyItem -> PageRenderer.drawSticky(canvas, item)
                is ConnectorItem -> PageRenderer.drawConnector(canvas, item, context)
            }
        }
        for (c in attached) PageRenderer.drawConnector(canvas, c, context)
        canvas.restore()
    }

    fun drawTransformedBox(canvas: Canvas, pageIndex: Int, b: Bounds, t: Affine) {
        path.reset()
        for (k in 0 until 4) {
            val x = if (k == 0 || k == 3) b.left else b.right
            val y = if (k < 2) b.top else b.bottom
            e.pageToView(pageIndex, t.mapX(x, y), t.mapY(x, y), corner)
            if (k == 0) path.moveTo(corner[0], corner[1]) else path.lineTo(corner[0], corner[1])
        }
        path.close()
        val th = e.theme
        linePaint.color = th.withAlpha(th.selection, 0xCC)
        linePaint.strokeWidth = dp(1f)
        canvas.drawPath(path, linePaint)
    }

    fun drawGuides(canvas: Canvas, pageIndex: Int, guides: List<AlignmentGuides.Guide>) {
        val th = e.theme
        linePaint.color = th.accent
        linePaint.strokeWidth = dp(1f)
        val ext = dp(12f) / e.scale
        for (g in guides) {
            if (g.vertical) {
                e.pageToView(pageIndex, g.at, g.from - ext, pos); e.pageToView(pageIndex, g.at, g.to + ext, corner)
            } else {
                e.pageToView(pageIndex, g.from - ext, g.at, pos); e.pageToView(pageIndex, g.to + ext, g.at, corner)
            }
            canvas.drawLine(pos[0], pos[1], corner[0], corner[1], linePaint)
        }
    }

    fun drawTargetPage(canvas: Canvas, pageIndex: Int) {
        if (!e.pageClip(pageIndex, rect)) return
        val th = e.theme
        linePaint.color = th.withAlpha(th.accent, 0x99)
        linePaint.strokeWidth = dp(2f)
        rect.inset(-dp(2f), -dp(2f))
        canvas.drawRoundRect(rect, dp(4f), dp(4f), linePaint)
    }

    /** A small pill with the rotation in degrees. */
    fun drawAngle(canvas: Canvas, degrees: Float, cx: Float, cy: Float) {
        var d = degrees.roundToInt() % 360
        if (d > 180) d -= 360
        if (d <= -180) d += 360
        val text = "$d°"
        val th = e.theme
        labelPaint.typeface = font()
        labelPaint.textSize = dp(12f)
        labelPaint.color = th.surface
        val w = labelPaint.measureText(text) + dp(16f)
        val hgt = dp(22f)
        val y = (cy - dp(18f)).coerceAtLeast(hgt)
        rect.set(cx - w / 2f, y - hgt / 2f, cx + w / 2f, y + hgt / 2f)
        fillPaint.color = th.withAlpha(th.ink, 0xE6)
        canvas.drawRoundRect(rect, hgt / 2f, hgt / 2f, fillPaint)
        val fm = labelPaint.fontMetrics
        canvas.drawText(text, cx, y - (fm.ascent + fm.descent) / 2f, labelPaint)
    }
}
