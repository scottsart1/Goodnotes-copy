package com.inkwell.notes.ink.engine

import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.ink.TestInk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan

class HandwritingSegmentationTest {
    private var ids = 0

    /** A letter 14pt wide, x-height 20 on [baseline]; descenders reach 12pt below it. */
    private fun letter(x: Float, baseline: Float, descender: Boolean = false, ascender: Boolean = false): Stroke {
        val top = baseline - if (ascender) 34f else 20f
        val bottom = baseline + if (descender) 12f else 0f
        return TestInk.stroke("s${ids++}", x, top, x + 7f, bottom, x + 14f, top)
    }

    /**
     * A line of words: each word is [letters] letters 16pt apart (2pt gaps), words separated by
     * [wordGap]. Returns strokes and the stroke ids per word.
     */
    private fun line(baseline: Float, words: List<Int>, wordGap: Float = 14f, x0: Float = 0f, slope: Float = 0f, descenders: Set<Int> = emptySet()): Pair<List<Stroke>, List<Set<String>>> {
        val out = ArrayList<Stroke>()
        val groups = ArrayList<Set<String>>()
        var x = x0
        var k = 0
        for (n in words) {
            val g = LinkedHashSet<String>()
            repeat(n) {
                val s = letter(x, baseline + (x - x0) * slope, descender = k in descenders)
                out += s; g += s.id
                x += 16f; k++
            }
            groups += g
            x += wordGap - 2f
        }
        return out to groups
    }

    @Test fun linesThenWordsByGaps() {
        val (l1, w1) = line(100f, listOf(3, 2, 4))
        val (l2, w2) = line(160f, listOf(5, 1))
        val lines = HandwritingLayout.segment((l2 + l1).shuffled(java.util.Random(4)))
        assertEquals(2, lines.size)
        assertEquals(w1, lines[0].words.map { it.ids })
        assertEquals(w2, lines[1].words.map { it.ids })
        assertEquals(100f, lines[0].baseline, 0.01f)
        assertEquals(160f, lines[1].baseline, 0.01f)
    }

    @Test fun dotsAndAccentsJoinTheirLine() {
        val (l1, _) = line(100f, listOf(3, 3))
        val dot = TestInk.stroke("dot", 20f, 70f, 21f, 71f)
        val comma = TestInk.stroke("comma", 50f, 100f, 49f, 104f)
        val lines = HandwritingLayout.segment(l1 + dot + comma)
        assertEquals(1, lines.size)
        assertTrue(lines[0].words.flatMap { it.ids }.containsAll(listOf("dot", "comma")))
    }

    @Test fun wordGapThresholdAdaptsToTheWriting() {
        // Tight letters, clear word gaps.
        val t = HandwritingLayout.wordGapThreshold(listOf(1f, 2f, 1.5f, 9f, 2f, 10f), 20f)
        assertTrue(t in 2f..9f)
        // All gaps similar and wide: every gap is a word gap.
        assertTrue(HandwritingLayout.wordGapThreshold(listOf(12f, 13f, 12.5f), 20f) < 12f)
        // All gaps tiny: one word.
        assertTrue(HandwritingLayout.wordGapThreshold(listOf(1f, 2f, 1.5f), 20f) > 2f)
    }

    @Test fun straightenFindsTheBaselineDespiteDescenders() {
        // Rising line (slope -0.12 in y-down space) with descenders on several letters.
        val (strokes, _) = line(200f, listOf(4, 3, 5, 2), slope = -0.12f, descenders = setOf(1, 5, 9, 12))
        val angle = HandwritingLayout.baselineAngle(strokes)
        assertEquals(atan(-0.12f), angle, 0.015f)
        // After rotating by -angle the bottoms of plain letters line up.
        val cx = 150f; val cy = 180f
        val rotated = strokes.map { HandwritingLayout.rotate(it, -angle, cx, cy) }
        val bottoms = rotated.filterIndexed { i, _ -> i !in setOf(1, 5, 9, 12) }.map { s -> (0 until s.pointCount).maxOf { s.points[it * InkPoints.STRIDE + 1] } }
        assertTrue("spread ${bottoms.max() - bottoms.min()}", bottoms.max() - bottoms.min() < 1.5f)
    }

    @Test fun straightenPoolsSlopeAcrossLines() {
        val (a, _) = line(100f, listOf(5, 4), slope = 0.08f)
        val (b, _) = line(150f, listOf(3), slope = 0.08f) // a short last line further left
        assertEquals(atan(0.08f), HandwritingLayout.baselineAngle(a + b), 0.01f)
        val (flat, _) = line(100f, listOf(3, 3, 3))
        assertEquals(0f, HandwritingLayout.baselineAngle(flat), 0.002f)
    }

    @Test fun reflowKeepsOrderAndLineHeight() {
        val (l1, w1) = line(100f, listOf(3, 3, 3))
        val (l2, w2) = line(140f, listOf(3, 3))
        val strokes = l1 + l2
        // Width fits two 3-letter words (46pt each + 14pt gap = 106).
        val moves = HandwritingLayout.reflow(strokes, left = 0f, top = 80f, width = 110f)
        assertNotNull(moves)
        val words = w1 + w2
        fun leftOf(word: Set<String>) = word.minOf { id -> strokes.first { it.id == id }.points[0] + moves!!.getValue(id).first }
        fun baselineOf(word: Set<String>) = word.map { id -> 100f + (if (id in w2.flatten()) 40f else 0f) + moves!!.getValue(id).second }.first()
        // Five words, two per line: lines at 100, 140, 180; reading order kept.
        val expectedLines = listOf(100f, 100f, 140f, 140f, 180f)
        for ((i, w) in words.withIndex()) assertEquals("word $i", expectedLines[i], baselineOf(w), 0.01f)
        assertEquals(0f, leftOf(words[0]), 0.01f)
        assertEquals(60f, leftOf(words[1]), 0.5f)
        assertEquals(0f, leftOf(words[2]), 0.01f)
        // Every stroke of a word moves together.
        for (w in words) assertEquals(1, w.map { moves!!.getValue(it) }.distinct().size)
    }

    @Test fun reflowKeepsParagraphBreaks() {
        val (l1, _) = line(100f, listOf(3, 3))
        val (l2, w2) = line(220f, listOf(3)) // a blank line and more between paragraphs (pitch 40 from below)
        val (l3, _) = line(260f, listOf(3))
        val moves = HandwritingLayout.reflow(l1 + l2 + l3, 0f, 80f, 1000f)!!
        // Paragraph 2 still starts below a gap.
        val dy = moves.getValue(w2[0].first()).second
        assertTrue("dy $dy", 220f + dy > 100f + 40f * 1.5f)
    }

    @Test fun respaceScalesGapsOnly() {
        val (l1, w1) = line(100f, listOf(2, 2))
        val moves = HandwritingLayout.respace(l1, 2f)!!
        // Gap 14 -> 28: the second word moves 14.
        assertEquals(14f, moves.getValue(w1[1].first()).first, 0.01f)
        assertEquals(0f, moves.getValue(w1[1].first()).second, 0f)
    }

    @Test fun fontSizeFromTheWritingsLineHeight() {
        val strokes = ArrayList<Stroke>()
        // x-height letters with some ascenders (34pt above the baseline).
        var x = 0f
        for (i in 0 until 10) { strokes += letter(x, 100f, ascender = i % 3 == 0); x += 16f }
        val size = HandwritingLayout.estimateFontSize(strokes)
        assertTrue("size $size", size in 26f..50f)
        // Small writing gives a small font.
        val small = strokes.map { s -> s.copy(points = s.points.copyOf().also { p -> for (i in 0 until s.pointCount) { p[i * 5] *= 0.4f; p[i * 5 + 1] = 100f + (p[i * 5 + 1] - 100f) * 0.4f } }) }
        assertTrue(HandwritingLayout.estimateFontSize(small) < size * 0.6f)
    }
}
