package com.inkwell.notes.ink.render

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.ink.geometry.Bounds
import kotlin.math.max

/** What the live-ink presenter needs from the engine. */
internal interface WetInkHost {
    val scale: Float
    val frontLayer: FrontBufferLayer?
    /** InputPrefs.lowLatencyInk and the layer is attached. */
    val lowLatencyEnabled: Boolean
    fun pageMatrix(pageIndex: Int, out: Matrix)
    fun pageBoundsToView(pageIndex: Int, b: Bounds, out: RectF)
    fun pageClip(pageIndex: Int, out: RectF): Boolean
    fun invalidateOverlay()
    /** Runs [action] once the next overlay frame has been committed to the display. */
    fun afterNextFrameCommitted(action: () -> Unit)
}

/**
 * Shows the in-progress stroke with minimum latency. With low-latency ink the stroke goes to
 * the front buffer (only the dirty region is redrawn each event); otherwise, for highlighters
 * (which multiply with the page) and when front buffering fails, the overlay view draws it.
 * Both paths build the outline with the same [StrokeOutliner] as committed ink.
 */
class WetInkPresenter internal constructor(private val host: WetInkHost) {
    internal val stroke = WetStroke()
    private var useFront = false
    private var full = true
    private var drawnCount = 0
    private var hideAfterFrame = false
    private var generation = 0

    private val outliner = StrokeOutliner()
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val clip = RectF()
    private val dirtyPage = Bounds()
    private val dirtyView = RectF()
    private val tmpView = RectF()
    private val tmpBounds = Bounds()

    val isActive: Boolean get() = stroke.active
    /** True when the overlay (not the front buffer) must draw the wet stroke. */
    val drawsInOverlay: Boolean get() = stroke.active && !useFront

    internal fun begin(pageIndex: Int, pen: PenType, color: Int, width: Float, sensitivity: Float, allowFrontBuffer: Boolean) {
        stroke.begin(pageIndex, pen, color, width, sensitivity)
        generation++
        hideAfterFrame = false
        val front = host.frontLayer
        useFront = allowFrontBuffer && pen != PenType.HIGHLIGHTER && host.lowLatencyEnabled && front != null && front.isReady
        full = true
        drawnCount = 0
    }

    internal fun add(x: Float, y: Float, pressure: Float, tilt: Float, time: Float) = stroke.add(x, y, pressure, tilt, time)

    /** Replaces the predicted tail (page coordinates, InkPoints layout). */
    internal fun setPredicted(points: FloatArray, count: Int) {
        val n = minOf(count, WetStroke.MAX_PREDICTED)
        System.arraycopy(points, 0, stroke.predicted, 0, n * InkPoints.STRIDE)
        stroke.predictedCount = n
    }

    internal fun clearPredicted() { stroke.predictedCount = 0 }

    /** Pushes the latest samples to the screen. */
    internal fun refresh() {
        if (!stroke.active) return
        val front = host.frontLayer
        if (useFront && (front == null || front.hasFailed)) {
            useFront = false
        }
        if (!useFront) {
            host.invalidateOverlay()
            drawnCount = stroke.count
            return
        }
        val p = front!!.obtain()
        host.pageMatrix(stroke.pageIndex, matrix)
        matrix.getValues(p.matrix)
        p.points = stroke.points
        p.count = stroke.count
        p.predictedCount = stroke.predictedCount
        System.arraycopy(stroke.predicted, 0, p.predicted, 0, stroke.predictedCount * InkPoints.STRIDE)
        p.pen = stroke.pen; p.color = stroke.color; p.width = stroke.width; p.sensitivity = stroke.sensitivity
        p.hasClip = host.pageClip(stroke.pageIndex, p.clip)
        p.full = full
        if (!full) computeDirty(p.dirty)
        full = false
        drawnCount = stroke.count
        if (!front.render(p)) {
            useFront = false
            host.invalidateOverlay()
        }
    }

    /** View region whose outline changed since the last refresh (recent samples + both predicted tails). */
    private fun computeDirty(out: RectF) {
        val st = InkPoints.STRIDE
        val pts = stroke.points
        dirtyPage.setEmpty()
        val from = max(0, drawnCount - 4)
        for (i in from until stroke.count) dirtyPage.union(pts[i * st], pts[i * st + 1])
        // Old predicted tail must be erased, new one drawn.
        dirtyPage.union(stroke.predictedBounds)
        tmpBounds.setEmpty()
        for (i in 0 until stroke.predictedCount) tmpBounds.union(stroke.predicted[i * st], stroke.predicted[i * st + 1])
        stroke.predictedBounds.set(tmpBounds)
        dirtyPage.union(tmpBounds)
        if (dirtyPage.isEmpty) { out.setEmpty(); return }
        dirtyPage.outset(stroke.width * PenModel.maxFactor(stroke.pen) * 0.5f + 0.5f)
        host.pageBoundsToView(stroke.pageIndex, dirtyPage, out)
        out.inset(-3f, -3f)
    }

    /** The viewport moved: redraw the whole stroke at the new transform. */
    internal fun onViewportChanged() {
        if (stroke.active) {
            full = true
            refresh()
        } else if (host.frontLayer?.showing == true) {
            // A just-finished stroke must not stay behind at the old position.
            host.frontLayer?.hide()
            hideAfterFrame = false
        }
    }

    /** The stroke was committed; the overlay now shows it as pending. */
    internal fun finish() {
        val wasFront = useFront
        stroke.end()
        useFront = false
        host.invalidateOverlay()
        if (wasFront) hideAfterFrame = true
    }

    /** The stroke was discarded (palm, cancel): remove it immediately. */
    internal fun cancel() {
        stroke.end()
        useFront = false
        hideAfterFrame = false
        host.frontLayer?.hide()
        host.invalidateOverlay()
    }

    /** Called by the overlay after it drew a frame. */
    internal fun onOverlayDrawn() {
        if (!hideAfterFrame) return
        hideAfterFrame = false
        val gen = generation
        host.afterNextFrameCommitted {
            // A new stroke may already own the front buffer.
            if (gen == generation && !stroke.active) host.frontLayer?.hide()
        }
    }

    /** Draws the wet stroke in the overlay (fallback and highlighter). */
    internal fun drawInOverlay(canvas: Canvas) {
        if (!drawsInOverlay || stroke.count == 0) return
        canvas.save()
        if (host.pageClip(stroke.pageIndex, clip)) canvas.clipRect(clip)
        host.pageMatrix(stroke.pageIndex, matrix)
        canvas.concat(matrix)
        outliner.build(stroke.points, stroke.count, stroke.predicted, stroke.predictedCount, stroke.pen, stroke.width, stroke.sensitivity, path)
        InkPaints.configureStroke(paint, stroke.pen, stroke.color)
        canvas.drawPath(path, paint)
        canvas.restore()
        tmpView.setEmpty()
    }
}
