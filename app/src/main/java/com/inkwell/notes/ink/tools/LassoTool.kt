package com.inkwell.notes.ink.tools

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import com.inkwell.notes.core.model.LassoMode
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.ink.geometry.FloatList
import com.inkwell.notes.ink.geometry.Geo
import com.inkwell.notes.ink.model.Items

/**
 * Freeform or rectangle lasso (ToolState.lassoMode) filtered by ToolState.lassoFilter. While
 * drawing, the loop shows as marching ants closed back to its start. Items mostly inside are
 * selected (strokes by their samples, boxes by their centre; groups expand). A tap selects the
 * item under it (its whole group), a double tap on a text box or sticky note edits it, and a tap
 * on empty space clears the selection.
 */
class LassoTool : InkTool {
    override val type: ToolType get() = ToolType.LASSO
    private var active = false
    override val isActive: Boolean get() = active
    private var pageIndex = -1
    private val pts = FloatList(256)
    private var startVX = 0f
    private var startVY = 0f
    private var maxTravel = 0f
    private val path = Path()
    private val matrix = Matrix()

    override fun onDown(host: ToolHost, e: ToolEvent) {
        active = true
        pageIndex = e.pageIndex
        pts.clear()
        pts.add(e.x); pts.add(e.y)
        startVX = e.viewX; startVY = e.viewY
        maxTravel = 0f
        host.invalidateOverlay()
    }

    override fun onMove(host: ToolHost, e: ToolEvent) {
        if (!active) return
        val minStep = 2f / host.scale
        for (i in 0 until e.count) {
            val n = pts.size
            if (Geo.dist(pts[n - 2], pts[n - 1], e.px[i], e.py[i]) >= minStep) { pts.add(e.px[i]); pts.add(e.py[i]) }
            maxTravel = maxOf(maxTravel, Geo.dist(startVX, startVY, e.vx[i], e.vy[i]))
        }
        host.invalidateOverlay()
    }

    override fun onUp(host: ToolHost, e: ToolEvent) {
        if (!active) return
        onMove(host, e)
        active = false
        host.invalidateOverlay()
        val holder = host.page(pageIndex) ?: return
        if (!holder.isLoaded) return
        val tapSlop = 8f * host.density
        if (maxTravel < tapSlop) {
            tap(host, e)
            return
        }
        host.taps.reset()
        val poly = polygon(host)
        val ids = LassoSelector.select(holder.content, holder.index(), poly, host.toolState.lassoFilter)
        if (ids.isEmpty()) host.clearSelection() else host.setSelection(pageIndex, ids)
    }

    private fun tap(host: ToolHost, e: ToolEvent) {
        val holder = host.page(pageIndex) ?: return
        val x = e.x; val y = e.y
        val tol = 6f * host.density / host.scale
        val hit = holder.index().hitTest(x, y, tol) { LassoSelector.accepts(host.toolState.lassoFilter, it) }
        val double = host.taps.tap(e.eventTime, e.viewX, e.viewY, hit?.let { Items.id(it) })
        if (hit == null) { host.clearSelection(); return }
        if (double && (hit is TextItem || hit is StickyItem)) {
            host.requestEdit(pageIndex, hit, x, y)
            return
        }
        val ids = LassoSelector.expandGroups(holder.content, setOf(Items.id(hit)))
        host.setSelection(pageIndex, ids)
        host.listener?.onItemTapped(Items.ref(holder.id, hit))
    }

    private fun polygon(host: ToolHost): FloatArray {
        if (host.toolState.lassoMode == LassoMode.RECTANGLE) {
            return LassoSelector.rectPolygon(pts[0], pts[1], pts[pts.size - 2], pts[pts.size - 1])
        }
        return pts.toArray()
    }

    override fun onCancel(host: ToolHost) {
        active = false
        host.invalidateOverlay()
    }

    override fun drawOverlay(host: ToolHost, canvas: Canvas) {
        if (!active || pts.size < 4) return
        path.reset()
        if (host.toolState.lassoMode == LassoMode.RECTANGLE) {
            val x0 = pts[0]; val y0 = pts[1]; val x1 = pts[pts.size - 2]; val y1 = pts[pts.size - 1]
            path.moveTo(x0, y0); path.lineTo(x1, y0); path.lineTo(x1, y1); path.lineTo(x0, y1)
        } else {
            path.moveTo(pts[0], pts[1])
            var i = 2
            while (i + 1 < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
        }
        // Closed back to the start: the loop shows exactly what will be selected.
        path.close()
        host.pageMatrix(pageIndex, matrix)
        path.transform(matrix)
        val t = host.theme
        val d = host.density
        fillPaint.color = t.withAlpha(t.selection, 20)
        canvas.drawPath(path, fillPaint)
        // Marching ants: a light under-line with accent dashes moving along it.
        underPaint.color = t.withAlpha(t.surface, 0xCC)
        underPaint.strokeWidth = 2.5f * d
        canvas.drawPath(path, underPaint)
        val dash = 6f * d; val gap = 4f * d
        val phase = (SystemClock.uptimeMillis() % MARCH_PERIOD_MS) / MARCH_PERIOD_MS.toFloat() * (dash + gap)
        antsPaint.color = t.selection
        antsPaint.strokeWidth = 1.5f * d
        antsPaint.pathEffect = DashPathEffect(floatArrayOf(dash, gap), -phase)
        canvas.drawPath(path, antsPaint)
        // Keep the ants marching while the loop is drawn.
        host.invalidateOverlay()
    }

    companion object {
        private const val MARCH_PERIOD_MS = 600L
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val underPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
        private val antsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    }
}
