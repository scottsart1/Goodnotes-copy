package com.inkwell.notes.ink.render

import android.graphics.Path
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.Stroke
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns [StrokeGeometry] into an android.graphics.Path. One instance per thread. The live stroke
 * (front buffer or overlay) and the committed renderer both go through this class, so a stroke
 * looks identical before and after pen-up.
 */
class StrokeOutliner {
    val geometry = StrokeGeometry()

    fun build(stroke: Stroke, out: Path) {
        build(stroke.points, stroke.pointCount, null, 0, stroke.pen, stroke.width, stroke.pressureSensitivity, out)
    }

    fun build(
        points: FloatArray, count: Int, extra: FloatArray?, extraCount: Int,
        pen: PenType, width: Float, sensitivity: Float, out: Path,
    ) {
        out.reset()
        out.fillType = Path.FillType.WINDING
        val g = geometry
        g.compute(points, count, extra, extraCount, pen, width, sensitivity)
        if (g.count == 0) return
        if (g.isDot) {
            val r = g.dotRadius
            if (g.flatCaps) out.addRect(g.xs[0] - r, g.ys[0] - r, g.xs[0] + r, g.ys[0] + r, Path.Direction.CW)
            else out.addCircle(g.xs[0], g.ys[0], r, Path.Direction.CW)
            return
        }
        for (k in 0 until g.runCount) emitRun(g, k, out)
    }

    private fun emitRun(g: StrokeGeometry, k: Int, out: Path) {
        val a = g.runStart[k]; val b = g.runEnd[k]
        val lx = g.lx; val ly = g.ly; val rx = g.rx; val ry = g.ry
        val sa = g.slot(k, a); val sb = g.slot(k, b)
        out.moveTo(lx[sa], ly[sa])
        for (s in sa + 1..sb) {
            out.quadTo(lx[s - 1], ly[s - 1], (lx[s - 1] + lx[s]) * 0.5f, (ly[s - 1] + ly[s]) * 0.5f)
        }
        out.lineTo(lx[sb], ly[sb])
        if (g.flatCaps) out.lineTo(rx[sb], ry[sb]) else arc(out, g.xs[b], g.ys[b], g.rs[b], lx[sb], ly[sb])
        out.lineTo(rx[sb], ry[sb])
        for (s in sb - 1 downTo sa) {
            out.quadTo(rx[s + 1], ry[s + 1], (rx[s + 1] + rx[s]) * 0.5f, (ry[s + 1] + ry[s]) * 0.5f)
        }
        out.lineTo(rx[sa], ry[sa])
        if (g.flatCaps) out.lineTo(lx[sa], ly[sa]) else arc(out, g.xs[a], g.ys[a], g.rs[a], rx[sa], ry[sa])
        out.close()
    }

    /** Half-circle cap from (fromX, fromY) around (cx, cy), sweeping -PI. */
    private fun arc(out: Path, cx: Float, cy: Float, r: Float, fromX: Float, fromY: Float) {
        val start = atan2(fromY - cy, fromX - cx)
        val steps = if (r > 3f) 10 else 6
        for (i in 1..steps) {
            val ang = start - PI.toFloat() * i / steps
            out.lineTo(cx + r * cos(ang), cy + r * sin(ang))
        }
    }
}
