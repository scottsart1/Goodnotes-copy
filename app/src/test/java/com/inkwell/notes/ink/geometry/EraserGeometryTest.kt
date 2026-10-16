package com.inkwell.notes.ink.geometry

import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.ink.TestInk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EraserGeometryTest {
    private val st = InkPoints.STRIDE

    private fun first(p: FloatArray, ch: Int) = p[ch]
    private fun last(p: FloatArray, ch: Int) = p[(p.size / st - 1) * st + ch]

    @Test fun precisionSplitsInTheMiddleWithInterpolatedChannels() {
        // 11 samples along x = 0..100, time 0..100 ms, eraser circle at x=50 radius 5.
        val s = TestInk.line("a", 0f, 0f, 100f, 0f, samples = 11)
        val pieces = StrokeSplitter.erase(s.points, st, 50f, 0f, 50f, 0f, 5f)
        assertNotNull(pieces)
        assertEquals(2, pieces!!.size)
        val left = pieces[0]; val right = pieces[1]
        assertEquals(0f, first(left, InkPoints.X), 1e-4f)
        assertEquals(45f, last(left, InkPoints.X), 1e-3f)
        assertEquals(55f, first(right, InkPoints.X), 1e-3f)
        assertEquals(100f, last(right, InkPoints.X), 1e-4f)
        // Time is interpolated at the cut (sample i at i*10 ms, x = i*10).
        assertEquals(45f, last(left, InkPoints.TIME), 1e-2f)
        assertEquals(55f, first(right, InkPoints.TIME), 1e-2f)
        assertEquals(0.5f, last(left, InkPoints.PRESSURE), 1e-5f)
    }

    @Test fun precisionUntouchedReturnsNull() {
        val s = TestInk.line("a", 0f, 0f, 100f, 0f)
        assertNull(StrokeSplitter.erase(s.points, st, 50f, 30f, 60f, 30f, 5f))
    }

    @Test fun precisionAtTheStartKeepsTheRest() {
        val s = TestInk.line("a", 0f, 0f, 100f, 0f, samples = 11)
        val pieces = StrokeSplitter.erase(s.points, st, 0f, 0f, 0f, 0f, 12f)!!
        assertEquals(1, pieces.size)
        assertEquals(12f, first(pieces[0], InkPoints.X), 1e-3f)
        assertEquals(100f, last(pieces[0], InkPoints.X), 1e-4f)
    }

    @Test fun precisionFullyCoveredLeavesNothing() {
        val s = TestInk.line("a", 0f, 0f, 10f, 0f, samples = 5)
        val pieces = StrokeSplitter.erase(s.points, st, 5f, 0f, 5f, 0f, 20f)
        assertNotNull(pieces)
        assertTrue(pieces!!.isEmpty())
    }

    @Test fun precisionSweptEraserCutsWiderGap() {
        val s = TestInk.line("a", 0f, 0f, 100f, 0f, samples = 11)
        // Eraser moved horizontally from 30 to 60 along the stroke.
        val pieces = StrokeSplitter.erase(s.points, st, 30f, 0f, 60f, 0f, 2f)!!
        assertEquals(2, pieces.size)
        assertEquals(28f, last(pieces[0], InkPoints.X), 1e-3f)
        assertEquals(62f, first(pieces[1], InkPoints.X), 1e-3f)
    }

    @Test fun precisionDropsTinyRemnants() {
        val s = TestInk.line("a", 0f, 0f, 100f, 0f, samples = 11)
        val pieces = StrokeSplitter.erase(s.points, st, 50f, 0f, 50f, 0f, 49f, minPieceLength = 2f)!!
        // Remnants of length 1 at each end are dropped.
        assertTrue(pieces.isEmpty())
    }

    @Test fun precisionSingleDot() {
        val dot = TestInk.stroke("d", 5f, 5f)
        assertTrue(StrokeSplitter.erase(dot.points, st, 5f, 6f, 5f, 6f, 2f)!!.isEmpty())
        assertNull(StrokeSplitter.erase(dot.points, st, 50f, 6f, 50f, 6f, 2f))
    }

    @Test fun subPolylineInterpolatesEnds() {
        val s = TestInk.line("a", 0f, 0f, 100f, 0f, samples = 11)
        val sub = StrokeSplitter.subPolyline(s.points, st, 15f, 42f)!!
        assertEquals(15f, first(sub, InkPoints.X), 1e-3f)
        assertEquals(42f, last(sub, InkPoints.X), 1e-3f)
    }

    @Test fun segmentEraserRemovesSpanBetweenCrossings() {
        val h = TestInk.line("h", 0f, 50f, 100f, 50f)
        val v1 = TestInk.line("v1", 30f, 0f, 30f, 100f)
        val v2 = TestInk.line("v2", 70f, 0f, 70f, 100f)
        val cuts = SegmentEraser.crossings(h.points, st, listOf(v1.points, v2.points), includeSelf = true)
        assertEquals(2, cuts.size)
        assertEquals(30f, cuts[0], 1e-3f)
        assertEquals(70f, cuts[1], 1e-3f)
        // Touch between the crossings: the middle goes, both ends stay.
        val mid = SegmentEraser.removeSpan(h.points, st, 50f, cuts, 0.5f)
        assertEquals(2, mid.size)
        assertEquals(30f, last(mid[0], InkPoints.X), 1e-3f)
        assertEquals(70f, first(mid[1], InkPoints.X), 1e-3f)
        // Touch on the left end: only [0, 30] goes.
        val leftEnd = SegmentEraser.removeSpan(h.points, st, 10f, cuts, 0.5f)
        assertEquals(1, leftEnd.size)
        assertEquals(30f, first(leftEnd[0], InkPoints.X), 1e-3f)
        assertEquals(100f, last(leftEnd[0], InkPoints.X), 1e-3f)
    }

    @Test fun segmentEraserUsesCornersWhenNothingCrosses() {
        // An L: right along y=0 then down along x=50.
        val l = TestInk.stroke("l", 0f, 0f, 25f, 0f, 50f, 0f, 50f, 25f, 50f, 50f)
        val cuts = SegmentEraser.corners(l.points, st, minSegment = 2f)
        assertEquals(1, cuts.size)
        assertEquals(50f, cuts[0], 1e-3f)
        val rest = SegmentEraser.removeSpan(l.points, st, 10f, cuts, 0.5f)
        assertEquals(1, rest.size)
        assertEquals(50f, first(rest[0], InkPoints.X), 1e-3f)
        assertEquals(0f, first(rest[0], InkPoints.Y), 1e-3f)
    }

    @Test fun segmentEraserWithoutCutsRemovesEverything() {
        val h = TestInk.line("h", 0f, 50f, 100f, 50f)
        assertTrue(SegmentEraser.removeSpan(h.points, st, 40f, FloatArray(0), 0.5f).isEmpty())
    }

    @Test fun segmentEraserFindsSelfIntersections() {
        // A loop crossing itself at (50, 0).
        val loop = TestInk.stroke("o", 0f, 0f, 100f, 0f, 100f, 50f, 50f, 50f, 50f, -50f)
        val cuts = SegmentEraser.crossings(loop.points, st, emptyList(), includeSelf = true)
        assertEquals(2, cuts.size)
        assertEquals(50f, cuts[0], 1e-3f)
    }

    @Test fun wordClustererSeparatesWordsByGap() {
        fun box(id: String, l: Float, r: Float, t: Long) = WordClusterer.StrokeBox(id, Bounds(l, 0f, r, 20f), t, t + 200)
        // Word 1: three letters 20 wide with 4pt gaps; word 2 starts 30pt later. Written slowly.
        val strokes = listOf(
            box("a", 0f, 20f, 0), box("b", 24f, 44f, 3000), box("c", 48f, 68f, 6000),
            box("d", 98f, 118f, 9000), box("e", 122f, 142f, 12000),
        )
        assertEquals(setOf("a", "b", "c"), WordClusterer.wordOf("b", strokes))
        assertEquals(setOf("d", "e"), WordClusterer.wordOf("e", strokes))
        assertEquals(2, WordClusterer.words(strokes).size)
    }

    @Test fun wordClustererAttachesAccentsAndUsesTiming() {
        val i = WordClusterer.StrokeBox("i", Bounds(0f, 6f, 3f, 20f), 0, 100)
        val dot = WordClusterer.StrokeBox("dot", Bounds(0f, 0f, 3f, 3f), 150, 160)
        val n = WordClusterer.StrokeBox("n", Bounds(5f, 8f, 18f, 20f), 300, 500)
        val far = WordClusterer.StrokeBox("far", Bounds(80f, 8f, 95f, 20f), 600, 800)
        assertEquals(setOf("i", "dot", "n"), WordClusterer.wordOf("dot", listOf(i, dot, n, far)))
        // A gap of 0.5 x height joins only when written in quick succession.
        val h = 20f
        val a = WordClusterer.StrokeBox("a", Bounds(0f, 0f, 20f, h), 0, 100)
        val bQuick = WordClusterer.StrokeBox("b", Bounds(30f, 0f, 50f, h), 300, 400)
        val bSlow = WordClusterer.StrokeBox("b", Bounds(30f, 0f, 50f, h), 30_000, 30_100)
        assertTrue(WordClusterer.linked(a, bQuick, h))
        assertTrue(!WordClusterer.linked(a, bSlow, h))
    }
}
