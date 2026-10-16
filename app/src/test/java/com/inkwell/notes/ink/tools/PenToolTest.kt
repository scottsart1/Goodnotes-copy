package com.inkwell.notes.ink.tools

import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.InputPrefs
import com.inkwell.notes.core.model.PenConfig
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ToolState
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.ink.input.PressureCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pen capture through the real [PenTool] on a fake host: what ends up in the committed stroke. */
class PenToolTest {
    private val host = FakeToolHost(scale = 2f, density = 2f)
    private val st = InkPoints.STRIDE

    private fun pen(stabilization: Float = 0f, sensitivity: Float = 0.6f): PenTool {
        host.toolState = ToolState(tool = ToolType.PEN, pen = PenConfig.DefaultPen.copy(stabilization = stabilization, pressureSensitivity = sensitivity))
        // Gestures that could turn a test stroke into a shape or an erase stay off.
        host.inputPrefs = InputPrefs(holdToStraighten = false, autoShapeRecognition = false, scribbleToErase = false, circleToSelect = false, motionPrediction = false)
        return PenTool(ToolType.PEN)
    }

    private fun straightStroke(tool: PenTool, pressure: Float = 0.5f) {
        tool.onDown(host, host.down(20f, 20f, 1000L, pressure))
        tool.onMove(host, host.move(listOf(FakeToolHost.Sample(40f, 20f, 1008L, pressure), FakeToolHost.Sample(60f, 20f, 1016L, pressure))))
        tool.onMove(host, host.move(80f, 20f, 1024L, pressure))
        tool.onUp(host, host.move(100f, 20f, 1032L, pressure))
    }

    @Test fun commitsEverySampleInPagePointsWithTimesRelativeToPenDown() {
        val tool = pen(stabilization = 0f)
        straightStroke(tool)
        assertEquals(listOf("Ink"), host.commits)
        val s = host.content.strokes.single()
        assertEquals(5, s.pointCount)
        // View px / scale = page points; the down sample is t = 0 and the others follow the event clock.
        assertEquals(listOf(10f, 20f, 30f, 40f, 50f), (0 until 5).map { s.points[it * st] })
        assertEquals(listOf(0f, 8f, 16f, 24f, 32f), (0 until 5).map { s.points[it * st + InkPoints.TIME] })
        assertEquals(PenType.FOUNTAIN, s.pen)
        assertEquals(0.6f, s.pressureSensitivity, 0f)
        assertTrue(s.createdAt > 0L)
    }

    @Test fun pressureGoesThroughTheUsersCurve() {
        val tool = pen()
        host.inputPrefs = host.inputPrefs.copy(pressureCurve = 0.8f)
        straightStroke(tool, pressure = 0.3f)
        val s = host.content.strokes.single()
        val expected = PressureCurve.apply(0.3f, 0.8f)
        assertTrue(expected > 0.3f)
        // Every sample but the pen-up one carries the mapped pressure; pen-up reuses the previous value.
        for (i in 0 until s.pointCount) assertEquals(expected, s.points[i * st + InkPoints.PRESSURE], 1e-6f)
    }

    @Test fun penUpPressureDoesNotChangeTheTipThatWasShown() {
        val tool = pen()
        tool.onDown(host, host.down(20f, 20f, 0L, 0.6f))
        tool.onMove(host, host.move(60f, 20f, 8L, 0.6f))
        // The S Pen reports a near-zero pressure on the lift sample.
        tool.onUp(host, host.move(100f, 20f, 16L, 0.02f))
        val s = host.content.strokes.single()
        assertEquals(0.6f, s.points[(s.pointCount - 1) * st + InkPoints.PRESSURE], 1e-6f)
    }

    @Test fun predictedSamplesAreNeverCommitted() {
        val tool = pen()
        host.inputPrefs = host.inputPrefs.copy(motionPrediction = true)
        tool.onDown(host, host.down(20f, 20f, 0L))
        tool.onMove(host, host.move(listOf(FakeToolHost.Sample(40f, 20f, 8L)), willPredict = true))
        val predicted = host.event(0, listOf(FakeToolHost.Sample(60f, 20f, 12L), FakeToolHost.Sample(80f, 20f, 16L), FakeToolHost.Sample(100f, 20f, 20L)), stylus = true)
        tool.onPredicted(host, predicted)
        assertEquals(3, host.wetInk.stroke.predictedCount)
        tool.onUp(host, host.move(50f, 20f, 16L))
        val s = host.content.strokes.single()
        assertEquals(3, s.pointCount)
        assertEquals(25f, s.points[2 * st], 1e-4f)
        assertEquals(0, host.wetInk.stroke.predictedCount)
    }

    @Test fun strokeCarriesTheRecordingAnchorOfPenDown() {
        val tool = pen()
        host.anchor = AudioAnchor("rec-1", 61_500L)
        tool.onDown(host, host.down(20f, 20f, 0L))
        // The recording moves on while the stroke is drawn; the stroke stays anchored to its start.
        host.anchor = AudioAnchor("rec-1", 62_000L)
        tool.onUp(host, host.move(60f, 20f, 300L))
        assertEquals(AudioAnchor("rec-1", 61_500L), host.content.strokes.single().audio)
    }

    @Test fun stabilizationSmoothsJitterButKeepsEndpoints() {
        val tool = pen(stabilization = 0.6f)
        tool.onDown(host, host.down(0f, 100f, 0L))
        var t = 0L
        val samples = ArrayList<FakeToolHost.Sample>()
        for (i in 1..60) { t += 4; samples += FakeToolHost.Sample(i * 4f, 100f + if (i % 2 == 0) 3f else -3f, t) }
        tool.onMove(host, host.move(samples))
        tool.onUp(host, host.move(244f, 100f, t + 4))
        val s = host.content.strokes.single()
        assertEquals(0f, s.points[0], 0f)
        assertEquals(50f, s.points[1], 0f)
        var maxDev = 0f
        for (i in 5 until s.pointCount) maxDev = maxOf(maxDev, kotlin.math.abs(s.points[i * st + 1] - 50f))
        assertTrue("jitter $maxDev", maxDev < 1.2f)
    }

    @Test fun fingerInkUsesConstantPressureAndNoCurve() {
        val tool = pen()
        host.inputPrefs = host.inputPrefs.copy(pressureCurve = 0.9f)
        tool.onDown(host, host.down(20f, 20f, 0L, 0.1f, stylus = false))
        tool.onUp(host, host.move(60f, 20f, 8L, 0.9f, stylus = false))
        val s = host.content.strokes.single()
        for (i in 0 until s.pointCount) assertEquals(PenTool.FINGER_PRESSURE, s.points[i * st + InkPoints.PRESSURE], 0f)
    }

    @Test fun cancelLeavesNoMarksAndNoHistory() {
        val tool = pen()
        tool.onDown(host, host.down(20f, 20f, 0L))
        tool.onMove(host, host.move(60f, 20f, 8L))
        tool.onCancel(host)
        assertFalse(tool.isActive)
        assertTrue(host.content.strokes.isEmpty())
        assertTrue(host.commits.isEmpty())
        assertFalse(host.wetInk.isActive)
    }

    @Test fun highlighterColourCarriesOpacityInAlpha() {
        host.toolState = ToolState(tool = ToolType.HIGHLIGHTER)
        host.inputPrefs = InputPrefs(holdToStraighten = false, autoShapeRecognition = false, scribbleToErase = false)
        val tool = PenTool(ToolType.HIGHLIGHTER)
        tool.onDown(host, host.down(20f, 20f, 0L))
        tool.onUp(host, host.move(60f, 20f, 8L))
        val s = host.content.strokes.single()
        assertEquals(PenType.HIGHLIGHTER, s.pen)
        assertNotNull(s)
        assertEquals((255 * 0.45f).toInt(), (s.color ushr 24) and 0xFF)
        assertEquals(PenConfig.DefaultHighlighter.color and 0xFFFFFF, s.color and 0xFFFFFF)
    }

    @Test fun flushCommitsTheStrokeInProgress() {
        val tool = pen()
        tool.onDown(host, host.down(20f, 20f, 0L))
        tool.onMove(host, host.move(60f, 20f, 8L))
        tool.flush(host)
        assertEquals(listOf("Ink"), host.commits)
        assertFalse(tool.isActive)
        assertEquals(2, host.content.strokes.single().pointCount)
    }
}
