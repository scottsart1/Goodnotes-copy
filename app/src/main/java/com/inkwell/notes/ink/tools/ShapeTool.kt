package com.inkwell.notes.ink.tools

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.ink.render.PageRenderer

/**
 * Drag to create a ShapeItem of ToolState.shape.kind: lines and arrows from start to end,
 * boxes corner to corner, polygons inscribed in the dragged box. A live preview is drawn in the
 * overlay with the same renderer as committed shapes.
 */
class ShapeTool : InkTool {
    override val type: ToolType get() = ToolType.SHAPE
    private var active = false
    override val isActive: Boolean get() = active
    private var pageIndex = -1
    private var x0 = 0f
    private var y0 = 0f
    private var x1 = 0f
    private var y1 = 0f
    private val matrix = Matrix()
    private val clip = RectF()

    override fun onDown(host: ToolHost, e: ToolEvent) {
        active = host.isEditable(e.pageIndex)
        if (!active) return
        host.clearSelection()
        pageIndex = e.pageIndex
        x0 = e.x; y0 = e.y; x1 = e.x; y1 = e.y
        host.invalidateOverlay()
    }

    override fun onMove(host: ToolHost, e: ToolEvent) {
        if (!active) return
        x1 = e.x; y1 = e.y
        host.invalidateOverlay()
    }

    override fun onUp(host: ToolHost, e: ToolEvent) {
        if (!active) return
        x1 = e.x; y1 = e.y
        active = false
        host.invalidateOverlay()
        val cfg = host.toolState.shape
        if (ShapeBuilder.isTooSmall(cfg.kind, x0, y0, x1, y1, 3f * host.density / host.scale)) return
        host.commitNewItems(pageIndex, listOf(build(host, host.newId(), host.nextZ(pageIndex))), "Shape")
    }

    private fun build(host: ToolHost, id: String, z: Int): ShapeItem {
        val cfg = host.toolState.shape
        return ShapeItem(
            id = id,
            kind = cfg.kind,
            points = ShapeBuilder.points(cfg.kind, x0, y0, x1, y1),
            strokeColor = cfg.strokeColor,
            fillColor = cfg.fillColor,
            strokeWidth = cfg.strokeWidth.coerceIn(0.25f, 60f),
            z = z,
            createdAt = System.currentTimeMillis(),
            audio = host.audioAnchor(),
        )
    }

    override fun onCancel(host: ToolHost) {
        active = false
        host.invalidateOverlay()
    }

    override fun flush(host: ToolHost) {
        if (active) onUp(host, ToolEvent().also { it.reset(pageIndex); it.add(x1, y1, 0f, 0f, 0f, 0f, 0L) })
    }

    override fun drawOverlay(host: ToolHost, canvas: Canvas) {
        if (!active) return
        canvas.save()
        if (host.pageClip(pageIndex, clip)) canvas.clipRect(clip)
        host.pageMatrix(pageIndex, matrix)
        canvas.concat(matrix)
        PageRenderer.drawShape(canvas, build(host, "preview", 0))
        canvas.restore()
    }
}
