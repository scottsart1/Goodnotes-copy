package com.inkwell.notes.ink.tools

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import com.inkwell.notes.core.model.LassoMode
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.ink.geometry.FloatList
import com.inkwell.notes.ink.geometry.Geo
import com.inkwell.notes.ink.model.Items

/**
 * Freeform or rectangle lasso. Items mostly inside the loop are selected (groups expand); a tap
 * selects the item under it or clears the selection and reports the tap.
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
    private val tmp = FloatArray(2)

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
            tap(host, e.x, e.y)
            return
        }
        val poly = polygon(host)
        val ids = LassoSelector.select(holder.content, holder.index(), poly, host.toolState.lassoFilter)
        if (ids.isEmpty()) host.clearSelection() else host.setSelection(pageIndex, ids)
    }

    private fun tap(host: ToolHost, x: Float, y: Float) {
        val holder = host.page(pageIndex) ?: return
        val tol = 6f * host.density / host.scale
        val hit = holder.index().hitTest(x, y, tol) { LassoSelector.accepts(host.toolState.lassoFilter, it) }
        if (hit == null) { host.clearSelection(); return }
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
            path.moveTo(x0, y0); path.lineTo(x1, y0); path.lineTo(x1, y1); path.lineTo(x0, y1); path.close()
        } else {
            path.moveTo(pts[0], pts[1])
            var i = 2
            while (i + 1 < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
        }
        host.pageMatrix(pageIndex, matrix)
        path.transform(matrix)
        val t = host.theme
        fillPaint.color = t.withAlpha(t.selection, 22)
        canvas.drawPath(path, fillPaint)
        linePaint.color = t.selection
        linePaint.strokeWidth = 1.5f * host.density
        linePaint.pathEffect = DashPathEffect(floatArrayOf(6f * host.density, 4f * host.density), 0f)
        canvas.drawPath(path, linePaint)
    }

    companion object {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    }
}
