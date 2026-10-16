package com.inkwell.notes.ink.tools

import android.graphics.Canvas
import android.graphics.Paint
import com.inkwell.notes.core.model.EraserConfig
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.ink.geometry.Bounds
import java.util.UUID

/**
 * Stroke, precision, segment and word eraser. Changes apply live while the eraser moves and the
 * whole gesture becomes one undo step. Size is in screen dp, so it feels the same at any zoom.
 */
class EraserTool : InkTool {
    override val type: ToolType get() = ToolType.ERASER
    private var session: EditSession? = null
    private var pageIndex = -1
    private var lastX = 0f
    private var lastY = 0f
    private var cursorX = 0f
    private var cursorY = 0f
    private var showCursor = false
    private var config = EraserConfig()
    private val engine = EraserEngine { UUID.randomUUID().toString() }
    private val changed = Bounds()

    override val isActive: Boolean get() = session != null

    override fun onDown(host: ToolHost, e: ToolEvent) {
        session?.commit()
        session = null
        config = host.toolState.eraser
        cursorX = e.viewX; cursorY = e.viewY; showCursor = true
        host.invalidateOverlay()
        if (!host.isEditable(e.pageIndex)) return
        host.clearSelection()
        pageIndex = e.pageIndex
        session = host.beginEdit("Erase")
        lastX = e.x; lastY = e.y
        erase(host, lastX, lastY, lastX, lastY)
    }

    override fun onMove(host: ToolHost, e: ToolEvent) {
        cursorX = e.viewX; cursorY = e.viewY
        host.invalidateOverlay()
        if (session == null) return
        for (i in 0 until e.count) {
            val x = e.px[i]; val y = e.py[i]
            erase(host, lastX, lastY, x, y)
            lastX = x; lastY = y
        }
    }

    override fun onUp(host: ToolHost, e: ToolEvent) {
        onMove(host, e)
        session?.commit()
        session = null
        showCursor = false
        host.invalidateOverlay()
    }

    override fun onCancel(host: ToolHost) {
        // A cancelled erase gesture (palm detected) restores everything it removed.
        session?.cancel()
        session = null
        showCursor = false
        host.invalidateOverlay()
    }

    override fun flush(host: ToolHost) {
        session?.commit()
        session = null
    }

    private fun erase(host: ToolHost, ax: Float, ay: Float, bx: Float, by: Float) {
        val s = session ?: return
        val content = s.content(pageIndex) ?: return
        val holder = host.page(pageIndex) ?: return
        val radius = config.sizeDp.coerceIn(2f, 200f) * host.density / 2f / host.scale
        changed.setEmpty()
        val next = engine.apply(content, holder.index(), config.mode, config.filter, ax, ay, bx, by, radius, changed)
        if (next !== content) s.update(pageIndex, next, changed.copy())
    }

    override fun drawOverlay(host: ToolHost, canvas: Canvas) {
        if (!showCursor) return
        drawCursor(host, canvas, cursorX, cursorY, host.toolState.eraser.sizeDp)
    }

    companion object {
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        /** Eraser cursor: a circle of the eraser's on-screen size. */
        fun drawCursor(host: ToolHost, canvas: Canvas, x: Float, y: Float, sizeDp: Float) {
            val r = sizeDp.coerceIn(2f, 200f) * host.density / 2f
            val t = host.theme
            fillPaint.color = t.withAlpha(t.surface, 90)
            canvas.drawCircle(x, y, r, fillPaint)
            ringPaint.color = t.inkMuted
            ringPaint.strokeWidth = host.density
            canvas.drawCircle(x, y, r, ringPaint)
        }
    }
}
