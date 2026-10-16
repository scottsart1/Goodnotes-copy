package com.inkwell.notes.ink.render

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PenType
import java.util.concurrent.ConcurrentLinkedQueue

/** One front-buffer draw request: an immutable snapshot of the wet stroke for the render thread. */
internal class FrontParam {
    /** Page -> view matrix values (android.graphics.Matrix order). */
    val matrix = FloatArray(9)
    var points: FloatArray = EMPTY
    var count = 0
    val predicted = FloatArray(WetStroke.MAX_PREDICTED * InkPoints.STRIDE)
    var predictedCount = 0
    var pen: PenType = PenType.BALLPOINT
    var color = 0
    var width = 1f
    var sensitivity = 0.5f
    /** Clear the whole layer and redraw the full stroke (viewport moved or first sample). */
    var full = false
    /** View-space region to clear and redraw when not [full]. */
    val dirty = RectF()
    /** View-space page rectangle to clip to (paged layouts). */
    val clip = RectF()
    var hasClip = false

    companion object {
        val EMPTY = FloatArray(0)
    }
}

/**
 * Low-latency ink: renders the in-progress stroke into a front buffer
 * (androidx.graphics.lowlatency.CanvasFrontBufferedRenderer) on a SurfaceView placed above the
 * canvas. Each update clears and redraws only the dirty region of the outline, so the predicted
 * tail is replaced cleanly and translucent pens never double blend. After pen-up the stroke is
 * shown by the normal view hierarchy and this layer is hidden. Any failure disables the layer;
 * callers then fall back to the overlay view.
 */
internal class FrontBufferLayer(context: Context, private val onFailure: () -> Unit) {
    val surfaceView: SurfaceView = SurfaceView(context).apply {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        isClickable = false
    }

    private var renderer: CanvasFrontBufferedRenderer<FrontParam>? = null
    @Volatile private var failed = false
    private val pool = ConcurrentLinkedQueue<FrontParam>()
    var attached = false
        private set
    /** True while the layer shows something that must be hidden later. */
    var showing = false
        private set

    // Render-thread objects.
    private val outliner = StrokeOutliner()
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()

    private val callback = object : CanvasFrontBufferedRenderer.Callback<FrontParam> {
        override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: FrontParam) {
            try {
                drawParam(canvas, param)
            } catch (t: Throwable) {
                Log.e(TAG, "Front buffer draw failed", t)
                failed = true
            } finally {
                pool.offer(param)
            }
        }

        override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, params: Collection<FrontParam>) {
            // Committed ink is drawn by the regular views; this layer stays transparent.
            canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
        }
    }

    val isReady: Boolean
        get() = !failed && attached && try { renderer?.isValid() == true } catch (t: Throwable) { false }

    fun attach(parent: ViewGroup) {
        if (attached || failed) return
        try {
            if (surfaceView.parent == null) parent.addView(surfaceView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            renderer = CanvasFrontBufferedRenderer(surfaceView, callback)
            attached = true
        } catch (t: Throwable) {
            Log.w(TAG, "Front-buffered rendering unavailable", t)
            fail()
        }
    }

    fun detach(parent: ViewGroup) {
        val r = renderer
        renderer = null
        attached = false
        showing = false
        try { r?.release(true) } catch (t: Throwable) { Log.w(TAG, "Front buffer release failed", t) }
        if (surfaceView.parent === parent) parent.removeView(surfaceView)
    }

    fun obtain(): FrontParam = pool.poll() ?: FrontParam()

    /** Queues a draw. Returns false when the layer is unusable (caller falls back). */
    fun render(param: FrontParam): Boolean {
        val r = renderer
        if (failed || r == null) { pool.offer(param); return false }
        return try {
            r.renderFrontBufferedLayer(param)
            showing = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Front buffer render failed", t)
            pool.offer(param)
            fail()
            false
        }
    }

    /** Hides and clears the front buffer (the stroke is now shown by the normal views). */
    fun hide() {
        if (!showing) return
        showing = false
        try { renderer?.cancel() } catch (t: Throwable) { Log.w(TAG, "Front buffer cancel failed", t); fail() }
    }

    val hasFailed: Boolean get() = failed

    private fun fail() {
        if (failed) return
        failed = true
        onFailure()
    }

    private fun drawParam(canvas: Canvas, p: FrontParam) {
        if (p.full) canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
        if (p.count == 0 && p.predictedCount == 0) return
        canvas.save()
        if (!p.full) {
            canvas.clipRect(p.dirty)
            canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
        }
        if (p.hasClip) canvas.clipRect(p.clip)
        matrix.setValues(p.matrix)
        canvas.concat(matrix)
        outliner.build(p.points, p.count, p.predicted, p.predictedCount, p.pen, p.width, p.sensitivity, path)
        InkPaints.configureStroke(paint, p.pen, p.color)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    companion object {
        private const val TAG = "InkFrontBuffer"
    }
}
