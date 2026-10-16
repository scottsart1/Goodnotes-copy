package com.inkwell.notes.ink.tools

import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PenConfig
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.ink.geometry.Geo
import com.inkwell.notes.ink.input.PointSmoother
import com.inkwell.notes.ink.input.PressureCurve

/**
 * Pen, pencil and highlighter. Samples are stabilised (one-euro filter), pressure goes through
 * the user's curve, the live stroke is shown by [com.inkwell.notes.ink.render.WetInkPresenter]
 * and pen-up commits exactly the samples that were shown (prediction is never committed).
 */
class PenTool(override val type: ToolType) : InkTool {
    override val isInking: Boolean get() = true
    private var active = false
    override val isActive: Boolean get() = active

    private val smoother = PointSmoother()
    private var cfg: PenConfig = PenConfig.DefaultPen
    private var color = 0
    private var pageIndex = -1
    private var startTime = 0L
    private var createdAt = 0L
    private var anchor: AudioAnchor? = null
    private var stylus = false
    private var lastPressure = 0.5f
    private val pred = FloatArray(com.inkwell.notes.ink.render.WetStroke.MAX_PREDICTED * InkPoints.STRIDE)

    private fun config(host: ToolHost): PenConfig = when (type) {
        ToolType.PENCIL -> host.toolState.pencil
        ToolType.HIGHLIGHTER -> host.toolState.highlighter
        else -> host.toolState.pen
    }

    override fun onDown(host: ToolHost, e: ToolEvent) {
        active = false
        if (!host.isEditable(e.pageIndex)) return
        host.clearSelection()
        cfg = config(host)
        val baseAlpha = (cfg.color ushr 24) and 0xFF
        val alpha = (baseAlpha * cfg.opacity.coerceIn(0.05f, 1f)).toInt().coerceIn(1, 255)
        color = (alpha shl 24) or (cfg.color and 0x00FFFFFF)
        smoother.configure(cfg.stabilization)
        smoother.reset()
        pageIndex = e.pageIndex
        startTime = e.eventTime
        createdAt = System.currentTimeMillis()
        anchor = host.audioAnchor()
        stylus = e.isStylus
        active = true
        host.wetInk.begin(pageIndex, cfg.type, color, cfg.width.coerceIn(0.1f, 200f), cfg.pressureSensitivity, allowFrontBuffer = !e.isFinger)
        addSamples(host, e, isUp = false)
        host.wetInk.refresh()
    }

    override fun onMove(host: ToolHost, e: ToolEvent) {
        if (!active) return
        addSamples(host, e, isUp = false)
        if (!e.willPredict) {
            host.wetInk.clearPredicted()
            host.wetInk.refresh()
        }
    }

    override fun onPredicted(host: ToolHost, e: ToolEvent) {
        if (!active) return
        val wet = host.wetInk.stroke
        val n = minOf(e.count, com.inkwell.notes.ink.render.WetStroke.MAX_PREDICTED)
        if (n == 0 || wet.count == 0) {
            host.wetInk.clearPredicted()
        } else {
            // Predictions start at the raw pen position; shift them so they continue the
            // smoothed stroke without a kink.
            val dx = wet.lastX() - lastRawX; val dy = wet.lastY() - lastRawY
            val st = InkPoints.STRIDE
            val tLast = wet.points[(wet.count - 1) * st + InkPoints.TIME]
            for (i in 0 until n) {
                pred[i * st] = e.px[i] + dx
                pred[i * st + 1] = e.py[i] + dy
                pred[i * st + InkPoints.PRESSURE] = lastPressure
                pred[i * st + InkPoints.TILT] = e.tilt[i]
                pred[i * st + InkPoints.TIME] = tLast + (i + 1) * 4f
            }
            host.wetInk.setPredicted(pred, n)
        }
        host.wetInk.refresh()
    }

    override fun onUp(host: ToolHost, e: ToolEvent) {
        if (!active) return
        addSamples(host, e, isUp = true)
        commit(host)
    }

    override fun flush(host: ToolHost) {
        if (active) commit(host)
    }

    private fun commit(host: ToolHost) {
        active = false
        val wet = host.wetInk.stroke
        if (wet.count == 0) { host.wetInk.cancel(); return }
        val stroke = Stroke(
            id = host.newId(),
            pen = cfg.type,
            color = color,
            width = cfg.width.coerceIn(0.1f, 200f),
            points = wet.committedPoints(),
            createdAt = createdAt,
            z = host.nextZ(pageIndex),
            audio = anchor,
            pressureSensitivity = cfg.pressureSensitivity,
        )
        host.commitNewItems(pageIndex, listOf(stroke), "Ink")
        host.wetInk.finish()
    }

    override fun onCancel(host: ToolHost) {
        if (!active) return
        active = false
        host.wetInk.cancel()
    }

    private var lastRawX = 0f
    private var lastRawY = 0f

    private fun addSamples(host: ToolHost, e: ToolEvent, isUp: Boolean) {
        val wet = host.wetInk
        val scale = host.scale
        val minDist = 0.35f / scale
        val curve = host.inputPrefs.pressureCurve
        for (i in 0 until e.count) {
            val pressure = if (stylus) PressureCurve.apply(e.pressure[i], curve) else FINGER_PRESSURE
            smoother.push(e.px[i], e.py[i], e.time[i], scale)
            lastRawX = e.px[i]; lastRawY = e.py[i]
            val x = smoother.outX; val y = smoother.outY
            val t = (e.time[i] - startTime).toFloat()
            val s = wet.stroke
            if (s.count > 0 && Geo.dist(s.lastX(), s.lastY(), x, y) < minDist) {
                // Pen-up pressure is ignored so the committed tip matches what was shown.
                if (!isUp) { s.updateLastPressure(pressure); lastPressure = pressure }
                continue
            }
            val p = if (isUp && s.count > 0) lastPressure else pressure
            wet.add(x, y, p, e.tilt[i], t)
            lastPressure = p
        }
    }

    companion object {
        /** Fingers and mice have no pressure sensor. */
        const val FINGER_PRESSURE = 0.6f
    }
}
