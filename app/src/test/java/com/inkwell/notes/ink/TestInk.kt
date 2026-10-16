package com.inkwell.notes.ink

import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.Stroke

/** Builders for test content. */
object TestInk {
    /** Stroke through (x, y) pairs with constant pressure 0.5 and 10 ms between samples. */
    fun stroke(
        id: String,
        vararg xy: Float,
        pen: PenType = PenType.BALLPOINT,
        width: Float = 2f,
        z: Int = 0,
        createdAt: Long = 1000L,
        audio: AudioAnchor? = null,
        color: Int = 0xFF000000.toInt(),
    ): Stroke {
        val n = xy.size / 2
        val p = FloatArray(n * InkPoints.STRIDE)
        for (i in 0 until n) {
            p[i * InkPoints.STRIDE] = xy[i * 2]
            p[i * InkPoints.STRIDE + 1] = xy[i * 2 + 1]
            p[i * InkPoints.STRIDE + InkPoints.PRESSURE] = 0.5f
            p[i * InkPoints.STRIDE + InkPoints.TILT] = 0f
            p[i * InkPoints.STRIDE + InkPoints.TIME] = i * 10f
        }
        return Stroke(id = id, pen = pen, color = color, width = width, points = p, createdAt = createdAt, z = z, audio = audio)
    }

    /** Densely sampled straight line from (x0,y0) to (x1,y1). */
    fun line(id: String, x0: Float, y0: Float, x1: Float, y1: Float, samples: Int = 21, pen: PenType = PenType.BALLPOINT, z: Int = 0, createdAt: Long = 1000L, width: Float = 2f): Stroke {
        val xy = FloatArray(samples * 2)
        for (i in 0 until samples) {
            val t = i / (samples - 1f)
            xy[i * 2] = x0 + (x1 - x0) * t
            xy[i * 2 + 1] = y0 + (y1 - y0) * t
        }
        return stroke(id, *xy, pen = pen, z = z, createdAt = createdAt, width = width)
    }

    fun rect(id: String, l: Float, t: Float, r: Float, b: Float, fill: Int = 0, z: Int = 0) =
        ShapeItem(id = id, kind = ShapeKind.RECTANGLE, points = floatArrayOf(l, t, r, b), strokeColor = 0xFF000000.toInt(), fillColor = fill, strokeWidth = 2f, z = z)

    fun xs(s: Stroke): List<Float> = (0 until s.pointCount).map { s.points[it * InkPoints.STRIDE] }
}
