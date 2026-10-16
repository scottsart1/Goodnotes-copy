package com.inkwell.notes.ink.render

import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.ink.geometry.Bounds

/**
 * The in-progress stroke. Samples are append-only; a fresh array is allocated per stroke (never
 * per sample) so the front-buffer render thread can safely read a prefix while the main thread
 * appends. Predicted samples are kept separately and never committed.
 */
internal class WetStroke {
    var active = false
        private set
    var pageIndex = -1
        private set
    var pen: PenType = PenType.BALLPOINT
        private set
    var color = 0
        private set
    var width = 1f
        private set
    var sensitivity = 0.5f
        private set

    var points = FloatArray(INITIAL * InkPoints.STRIDE)
        private set
    var count = 0
        private set
    val predicted = FloatArray(MAX_PREDICTED * InkPoints.STRIDE)
    var predictedCount = 0

    /** Bounds of committed samples (page points). */
    val bounds = Bounds()
    /** Bounds of the predicted tail last drawn (page points). */
    val predictedBounds = Bounds()

    fun begin(pageIndex: Int, pen: PenType, color: Int, width: Float, sensitivity: Float) {
        active = true
        this.pageIndex = pageIndex
        this.pen = pen; this.color = color; this.width = width; this.sensitivity = sensitivity
        points = FloatArray(INITIAL * InkPoints.STRIDE)
        count = 0
        predictedCount = 0
        bounds.setEmpty()
        predictedBounds.setEmpty()
    }

    fun add(x: Float, y: Float, pressure: Float, tilt: Float, time: Float) {
        val st = InkPoints.STRIDE
        if ((count + 1) * st > points.size) points = points.copyOf(points.size * 2)
        val o = count * st
        points[o] = x; points[o + 1] = y; points[o + 2] = pressure; points[o + 3] = tilt; points[o + 4] = time
        count++
        bounds.union(x, y)
    }

    /** Replaces the pressure of the newest sample (pen lifting without moving). */
    fun updateLastPressure(pressure: Float) {
        if (count > 0) points[(count - 1) * InkPoints.STRIDE + InkPoints.PRESSURE] = pressure
    }

    fun lastX(): Float = points[(count - 1) * InkPoints.STRIDE]
    fun lastY(): Float = points[(count - 1) * InkPoints.STRIDE + 1]

    fun committedPoints(): FloatArray = points.copyOf(count * InkPoints.STRIDE)

    fun end() {
        active = false
        predictedCount = 0
    }

    companion object {
        const val INITIAL = 512
        const val MAX_PREDICTED = 24
    }
}
