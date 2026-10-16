package com.inkwell.notes.ink.engine

import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.ink.TestInk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HandwritingLayoutTest {
    /** A "letter": a small zig-zag 16pt wide and 20pt tall with its top-left at (x, y). */
    private fun letter(id: String, x: Float, y: Float, t: Long) =
        TestInk.stroke(id, x, y, x + 8f, y + 20f, x + 16f, y, createdAt = t)

    /** Words of [letters] letters spaced 4pt, words 30pt apart, written slowly. */
    private fun line(y: Float, words: Int, letters: Int, startId: Int = 0, slope: Float = 0f): List<Stroke> {
        val out = ArrayList<Stroke>()
        var x = 0f
        var id = startId
        var t = 0L
        repeat(words) {
            repeat(letters) {
                out += letter("s${id++}", x, y + x * slope, t)
                x += 20f
                t += 3000
            }
            x += 30f
        }
        return out
    }

    @Test fun wordsAndLines() {
        val strokes = line(0f, words = 3, letters = 2) + line(60f, words = 2, letters = 3, startId = 100)
        val ws = HandwritingLayout.words(strokes)
        assertEquals(5, ws.size)
        val ls = HandwritingLayout.lines(ws)
        assertEquals(2, ls.size)
        assertEquals(3, ls[0].size)
        assertEquals(2, ls[1].size)
        assertTrue(ls[0][0].bounds.left < ls[0][1].bounds.left)
    }

    @Test fun baselineAngleOfASlopedLine() {
        val strokes = line(0f, words = 4, letters = 2, slope = 0.1f)
        val angle = HandwritingLayout.baselineAngle(strokes)
        assertEquals(kotlin.math.atan(0.1f), angle, 0.02f)
        assertEquals(0f, HandwritingLayout.baselineAngle(line(0f, 4, 2)), 0.01f)
    }

    @Test fun rotateStraightensAroundTheCentre() {
        val s = TestInk.stroke("a", 0f, 0f, 100f, 10f)
        val angle = kotlin.math.atan(0.1f)
        val r = HandwritingLayout.rotate(s, -angle, 50f, 5f)
        val y0 = r.points[1]; val y1 = r.points[InkPoints.STRIDE + 1]
        assertEquals(y0, y1, 1e-3f)
        // Pressure and time channels are untouched.
        assertEquals(s.points[InkPoints.TIME + InkPoints.STRIDE], r.points[InkPoints.TIME + InkPoints.STRIDE], 0f)
    }

    @Test fun reflowWrapsWordsIntoTheWidth() {
        // One line of 4 words, each 36pt wide with 30pt gaps (total 234pt).
        val strokes = line(0f, words = 4, letters = 2)
        val moves = HandwritingLayout.reflow(strokes, left = 0f, top = 0f, width = 120f)!!
        // Words 3 and 4 move to the next line.
        val word3 = moves.getValue("s4"); val word4 = moves.getValue("s6")
        assertTrue(word3.second > 20f)
        assertEquals(word3.second, word4.second, 1e-3f)
        assertEquals(0f, word3.first + strokes.first { it.id == "s4" }.points[0], 1e-3f)
        // The first word stays put.
        val w1 = moves.getValue("s0")
        assertEquals(0f, w1.first, 1e-3f)
        assertEquals(0f, w1.second, 1e-3f)
        // Already fitting: nothing moves.
        assertNull(HandwritingLayout.reflow(line(0f, words = 2, letters = 2), 0f, 0f, 1000f))
    }

    @Test fun respaceScalesGapsBetweenWords() {
        val strokes = line(0f, words = 3, letters = 2)
        val moves = HandwritingLayout.respace(strokes, 2f)!!
        // Original gaps are 34pt (36pt words, letters 20pt apart => word right edge at 36, next starts at 70).
        val second = moves.getValue("s2").first
        val third = moves.getValue("s4").first
        assertEquals(34f, second, 1e-3f)
        assertEquals(68f, third, 1e-3f)
        assertTrue(abs(moves.getValue("s3").first - second) < 1e-3f) // whole word moves together
        assertNull(HandwritingLayout.respace(line(0f, 1, 3), 2f))
    }
}
