package com.inkwell.notes.export.format

import android.graphics.Path
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.ink.render.PageRenderer
import kotlin.math.max

/** Splits the output of android.graphics.Path.approximate into polygons (packed x,y). */
object PolygonSplitter {
    /**
     * [approx] holds (fraction, x, y) triples. A move to a new contour repeats the previous
     * fraction at a different position; a repeated fraction at the same position is just a
     * zero-length segment and stays in the contour.
     */
    fun split(approx: FloatArray): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        var cur = ArrayList<Float>()
        var i = 0
        var prevF = Float.NaN
        var prevX = 0f
        var prevY = 0f
        while (i + 2 < approx.size) {
            val f = approx[i]; val x = approx[i + 1]; val y = approx[i + 2]
            val isMove = cur.isNotEmpty() && f == prevF && (x != prevX || y != prevY)
            if (isMove) {
                if (cur.size >= 6) out += cur.toFloatArray()
                cur = ArrayList()
            }
            if (cur.size < 2 || cur[cur.size - 2] != x || cur[cur.size - 1] != y) {
                cur += x; cur += y
            }
            prevF = f; prevX = x; prevY = y
            i += 3
        }
        if (cur.size >= 6) out += cur.toFloatArray()
        return out
    }
}

/** Android-backed geometry and text measurement used by the PDF writer. */
internal object AndroidGeometry {
    /** Max deviation (points) of the flattened outline from the true curve. */
    private const val TOLERANCE = 0.05f

    fun polygons(path: Path, tolerance: Float = TOLERANCE): List<FloatArray> =
        if (path.isEmpty) emptyList() else PolygonSplitter.split(path.approximate(tolerance))

    /** The filled outline of [stroke] exactly as the canvas draws it, as polygons in page points. */
    fun strokeOutline(stroke: Stroke): List<FloatArray> {
        if (stroke.pointCount == 0) return emptyList()
        val path = Path()
        PageRenderer.buildStrokeOutline(stroke, path)
        return polygons(path)
    }
}

/** One laid-out line of a text box: [left] and [baseline] are relative to the box's top-left. */
data class LaidLine(val text: String, val left: Float, val baseline: Float)

/**
 * Breaks text into lines exactly like the on-screen renderer (StaticLayout with the same
 * paint settings), so exported text wraps where the user saw it wrap.
 */
internal object TextLines {
    fun layout(text: String, widthPt: Float, sizePt: Float, bold: Boolean, italic: Boolean): List<LaidLine> {
        if (text.isEmpty()) return emptyList()
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePt.coerceIn(1f, 1000f)
            isFakeBoldText = bold
            textSkewX = if (italic) -0.2f else 0f
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, widthPt.toInt()))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        val out = ArrayList<LaidLine>(layout.lineCount)
        for (i in 0 until layout.lineCount) {
            val s = layout.getLineStart(i)
            val e = layout.getLineEnd(i)
            if (e <= s) continue
            val line = text.substring(s, e).trimEnd('\n', '\r', ' ', ' ', '\t')
            if (line.isEmpty()) continue
            out += LaidLine(line, layout.getLineLeft(i), layout.getLineBaseline(i).toFloat())
        }
        return out
    }
}
