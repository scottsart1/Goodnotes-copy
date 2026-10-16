package com.inkwell.notes.ink.tools

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.geometry.Geo
import com.inkwell.notes.ink.geometry.ItemGeometry
import com.inkwell.notes.ink.render.PageRenderer

/** HAND: the stylus pans the canvas (with fling). */
class HandTool : InkTool {
    override val type: ToolType get() = ToolType.HAND
    private var active = false
    override val isActive: Boolean get() = active
    private var lastX = 0f
    private var lastY = 0f
    private val hx = FloatArray(HISTORY)
    private val hy = FloatArray(HISTORY)
    private val ht = LongArray(HISTORY)
    private var hn = 0
    private var startX = 0f
    private var startY = 0f
    private var moved = false

    override fun onDown(host: ToolHost, e: ToolEvent) {
        host.stopViewportMotion()
        active = true
        lastX = e.viewX; lastY = e.viewY; startX = lastX; startY = lastY
        moved = false
        hn = 0
        record(e.viewX, e.viewY, e.eventTime)
    }

    override fun onMove(host: ToolHost, e: ToolEvent) {
        if (!active) return
        for (i in 0 until e.count) record(e.vx[i], e.vy[i], e.time[i])
        val dx = e.viewX - lastX; val dy = e.viewY - lastY
        if (!moved && Geo.dist(startX, startY, e.viewX, e.viewY) > 4f * host.density) moved = true
        lastX = e.viewX; lastY = e.viewY
        // The page moves under the pen, so subsequent view coordinates stay valid.
        host.panBy(dx, dy)
    }

    override fun onUp(host: ToolHost, e: ToolEvent) {
        if (!active) return
        active = false
        if (!moved) {
            tapItem(host, e)
            return
        }
        // Velocity from the last ~60 ms of samples.
        val n = hn.coerceAtMost(HISTORY)
        if (n >= 2) {
            val last = (hn - 1) % HISTORY
            var first = last
            for (k in 1 until n) {
                val idx = (hn - 1 - k) % HISTORY
                if (ht[last] - ht[idx] > 60) break
                first = idx
            }
            val dt = (ht[last] - ht[first]).coerceAtLeast(1)
            host.fling((hx[last] - hx[first]) * 1000f / dt, (hy[last] - hy[first]) * 1000f / dt)
        }
    }

    private fun tapItem(host: ToolHost, e: ToolEvent) {
        val holder = host.page(e.pageIndex) ?: return
        if (!holder.isLoaded) return
        val hit = holder.index().hitTest(e.x, e.y, 6f * host.density / host.scale) ?: return
        host.listener?.onItemTapped(com.inkwell.notes.ink.model.Items.ref(holder.id, hit))
    }

    private fun record(x: Float, y: Float, t: Long) {
        val i = hn % HISTORY
        hx[i] = x; hy[i] = y; ht[i] = t
        hn++
    }

    override fun onCancel(host: ToolHost) { active = false }

    companion object { private const val HISTORY = 16 }
}

/** Base for tools acting on a tap (TEXT, IMAGE, STICKY): fires when the pointer lifts without moving. */
abstract class TapTool : InkTool {
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var active = false
    override val isActive: Boolean get() = active

    override fun onDown(host: ToolHost, e: ToolEvent) {
        active = true
        downX = e.viewX; downY = e.viewY; moved = false
    }

    override fun onMove(host: ToolHost, e: ToolEvent) {
        if (Geo.dist(downX, downY, e.viewX, e.viewY) > 10f * host.density) moved = true
    }

    override fun onUp(host: ToolHost, e: ToolEvent) {
        if (!active) return
        active = false
        if (!moved && Geo.dist(downX, downY, e.viewX, e.viewY) <= 10f * host.density) onTap(host, e)
    }

    override fun onCancel(host: ToolHost) { active = false }

    protected abstract fun onTap(host: ToolHost, e: ToolEvent)
}

/** TEXT: tap an existing text box to edit it, or anywhere else to create one. */
class TextTool : TapTool() {
    override val type: ToolType get() = ToolType.TEXT
    private val rect = RectF()

    override fun onTap(host: ToolHost, e: ToolEvent) {
        val holder = host.page(e.pageIndex) ?: return
        if (!host.isEditable(e.pageIndex)) return
        host.clearSelection()
        val hit = holder.index().hitTest(e.x, e.y, 4f * host.density / host.scale) { it is TextItem } as TextItem?
        if (hit != null) {
            host.pageBoundsToView(e.pageIndex, ItemGeometry.bounds(hit, Bounds()), rect)
            host.listener?.onTextEditRequested(holder.id, hit.id, e.x, e.y, RectF(rect))
        } else {
            val cfg = host.toolState.text
            val b = Bounds(e.x, e.y, e.x + DEFAULT_TEXT_WIDTH, e.y + cfg.fontSizePt * 1.5f)
            host.pageBoundsToView(e.pageIndex, b, rect)
            host.listener?.onTextEditRequested(holder.id, null, e.x, e.y, RectF(rect))
        }
    }

    companion object { const val DEFAULT_TEXT_WIDTH = 280f }
}

/** IMAGE: tap to ask the host for an image to place there. */
class ImageTool : TapTool() {
    override val type: ToolType get() = ToolType.IMAGE
    override fun onTap(host: ToolHost, e: ToolEvent) {
        if (!host.isEditable(e.pageIndex)) return
        host.listener?.onImagePlacementRequested(e.pageIndex, e.x, e.y)
    }
}

/** What the sticky tool needs beyond [ToolHost]. */
interface StickyHost {
    /** Adds a sticky centred at the point; returns its id. */
    fun addStickyAt(pageIndex: Int, x: Float, y: Float): String?
}

/** STICKY: tap a sticky to edit it, or empty space to add one. */
class StickyTool(private val stickies: StickyHost) : TapTool() {
    override val type: ToolType get() = ToolType.STICKY
    private val rect = RectF()

    override fun onTap(host: ToolHost, e: ToolEvent) {
        val holder = host.page(e.pageIndex) ?: return
        if (!host.isEditable(e.pageIndex)) return
        val hit = holder.index().hitTest(e.x, e.y, 2f * host.density / host.scale) { it is StickyItem } as StickyItem?
        val id = hit?.id ?: stickies.addStickyAt(e.pageIndex, e.x, e.y) ?: return
        val item = host.page(e.pageIndex)?.content?.stickies?.firstOrNull { it.id == id } ?: return
        host.pageBoundsToView(e.pageIndex, Bounds(item.x, item.y, item.x + item.width, item.y + item.height), rect)
        host.listener?.onStickyEditRequested(holder.id, id, RectF(rect))
    }
}

/** CONNECTOR: drag between items to link them with an arrow that follows them. */
class ConnectorTool : InkTool {
    override val type: ToolType get() = ToolType.CONNECTOR
    private var active = false
    override val isActive: Boolean get() = active
    private var pageIndex = -1
    private var x0 = 0f
    private var y0 = 0f
    private var x1 = 0f
    private var y1 = 0f
    private var fromId: String? = null
    private val matrix = Matrix()
    private val clip = RectF()

    private fun attachable(item: Any) = item is StickyItem || item is ShapeItem || item is TextItem || item is ImageItem

    override fun onDown(host: ToolHost, e: ToolEvent) {
        active = host.isEditable(e.pageIndex)
        if (!active) return
        host.clearSelection()
        pageIndex = e.pageIndex
        x0 = e.x; y0 = e.y; x1 = e.x; y1 = e.y
        val holder = host.page(pageIndex) ?: return
        fromId = holder.index().hitTest(x0, y0, 4f * host.density / host.scale, ::attachable)?.let { com.inkwell.notes.ink.model.Items.id(it) }
    }

    override fun onMove(host: ToolHost, e: ToolEvent) {
        if (!active) return
        x1 = e.x; y1 = e.y
        host.invalidateOverlay()
    }

    override fun onUp(host: ToolHost, e: ToolEvent) {
        if (!active) return
        active = false
        x1 = e.x; y1 = e.y
        host.invalidateOverlay()
        if (Geo.dist(x0, y0, x1, y1) < 8f * host.density / host.scale) return
        val holder = host.page(pageIndex) ?: return
        val toId = holder.index().hitTest(x1, y1, 4f * host.density / host.scale, ::attachable)?.let { com.inkwell.notes.ink.model.Items.id(it) }
        val c = ConnectorItem(
            id = host.newId(),
            fromItemId = fromId, toItemId = toId?.takeIf { it != fromId },
            fromX = x0, fromY = y0, toX = x1, toY = y1,
            color = host.toolState.shape.strokeColor,
            width = host.toolState.shape.strokeWidth.coerceIn(0.5f, 20f),
            z = host.nextZ(pageIndex),
            createdAt = System.currentTimeMillis(),
        )
        host.commitNewItems(pageIndex, listOf(c), "Connector")
    }

    override fun onCancel(host: ToolHost) {
        active = false
        host.invalidateOverlay()
    }

    override fun drawOverlay(host: ToolHost, canvas: Canvas) {
        if (!active) return
        val holder = host.page(pageIndex) ?: return
        canvas.save()
        if (host.pageClip(pageIndex, clip)) canvas.clipRect(clip)
        host.pageMatrix(pageIndex, matrix)
        canvas.concat(matrix)
        val preview = ConnectorItem("preview", fromItemId = fromId, fromX = x0, fromY = y0, toX = x1, toY = y1,
            color = host.toolState.shape.strokeColor, width = host.toolState.shape.strokeWidth.coerceIn(0.5f, 20f))
        PageRenderer.drawConnector(canvas, preview, holder.content)
        canvas.restore()
    }
}

/** Maps tool types to tool instances. Other features may replace or add tools here. */
class ToolRegistry(stickies: StickyHost) {
    private val tools = HashMap<ToolType, InkTool>()

    init {
        register(PenTool(ToolType.PEN))
        register(PenTool(ToolType.PENCIL))
        register(PenTool(ToolType.HIGHLIGHTER))
        register(EraserTool())
        register(LassoTool())
        register(ShapeTool())
        register(HandTool())
        register(TextTool())
        register(ImageTool())
        register(StickyTool(stickies))
        register(ConnectorTool())
    }

    fun register(tool: InkTool) { tools[tool.type] = tool }

    operator fun get(type: ToolType): InkTool = tools[type] ?: tools.getValue(ToolType.PEN)

    val all: Collection<InkTool> get() = tools.values
}
