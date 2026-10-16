package com.inkwell.notes.ocr

import com.inkwell.notes.ocr.ink.InkLines
import com.inkwell.notes.ocr.ink.StrokeBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkLinesTest {
    private var nextIndex = 0
    private var clock = 1_000L

    /** A letter-sized stroke (10 wide, [h] high) whose box starts at x and has its centre at cy. */
    private fun letter(x: Float, cy: Float, h: Float = 12f, w: Float = 10f, time: Long? = null) =
        StrokeBox(nextIndex++, x, cy - h / 2, x + w, cy + h / 2, time ?: clock.also { clock += 100 })

    /** A word of [n] letters starting at x on a line centred at cy. */
    private fun word(x: Float, cy: Float, n: Int, slope: Float = 0f): List<StrokeBox> =
        (0 until n).map { i -> letter(x + i * 11f, cy + slope * (x + i * 11f)) }

    @Test
    fun emptyInputHasNoLines() {
        assertTrue(InkLines.cluster(emptyList()).isEmpty())
    }

    @Test
    fun separatesLinesTopToBottomAndWordsLeftToRight() {
        val line2 = word(20f, 100f, 4) + word(90f, 100f, 3)
        val line1 = word(20f, 60f, 3) + word(80f, 60f, 5)
        val lines = InkLines.cluster(line2 + line1)
        assertEquals(2, lines.size)
        assertTrue(lines[0].top < lines[1].top)
        assertEquals(line1.map { it.index }.toSet(), lines[0].order.toSet())
        assertEquals(2, lines[0].words.size)
        assertEquals(3, lines[0].words[0].size)
        assertEquals(5, lines[0].words[1].size)
        assertEquals(listOf(4, 3), lines[1].words.map { it.size })
    }

    @Test
    fun dotsAndAccentsJoinTheirLine() {
        val letters = word(20f, 100f, 5)
        // An i-dot just above the x-height, drawn last.
        val dot = StrokeBox(nextIndex++, 33f, 89f, 35f, 91f, 99_999L)
        val lines = InkLines.cluster(letters + dot)
        assertEquals(1, lines.size)
        assertTrue(dot.index in lines[0].order)
    }

    @Test
    fun aDotBetweenTwoLinesBelongsToTheLineBelow() {
        val upper = word(20f, 70f, 5)
        val lower = word(20f, 100f, 5)
        val dot = StrokeBox(nextIndex++, 44f, 86f, 46f, 88f, 99_999L)
        val lines = InkLines.cluster(upper + lower + dot)
        assertEquals(2, lines.size)
        assertTrue(dot.index in lines[1].order)
    }

    @Test
    fun ascendersAndBracketsJoinTheirLine() {
        val letters = word(20f, 100f, 6)
        val tall = StrokeBox(nextIndex++, 90f, 80f, 92f, 112f, 50_000L) // 32 high: "l" / bracket
        val lines = InkLines.cluster(letters + tall)
        assertEquals(1, lines.size)
        assertTrue(tall.index in lines[0].order)
    }

    @Test
    fun slantedLinesHoldTogether() {
        // Drifts down 0.15pt per pt: 30pt over the line, more than a letter height.
        val line = word(10f, 100f, 6, slope = 0.15f) + word(90f, 100f, 6, slope = 0.15f) + word(170f, 100f, 6, slope = 0.15f)
        val lines = InkLines.cluster(line)
        assertEquals(1, lines.size)
        assertEquals(3, lines[0].words.size)
    }

    @Test
    fun drawingsAreLeftOut() {
        val letters = word(20f, 100f, 6)
        val drawing = StrokeBox(nextIndex++, 300f, 50f, 500f, 300f, 7L)
        val lines = InkLines.cluster(letters + drawing)
        assertEquals(1, lines.size)
        assertTrue(drawing.index !in lines.flatMap { it.order })
    }

    @Test
    fun farApartTextOnOneHeightBecomesSeparateColumns() {
        val left = word(10f, 100f, 4)
        val right = word(400f, 100f, 4)
        val lines = InkLines.cluster(right + left)
        assertEquals(2, lines.size)
        assertEquals(left.map { it.index }, lines[0].order)
        assertEquals(right.map { it.index }, lines[1].order)
    }

    @Test
    fun wordsAreOrderedByPositionAndStrokesWithinAWordByTime() {
        // A word inserted later between two earlier words is read in place.
        val first = word(10f, 100f, 3)
        val third = word(120f, 100f, 3)
        val inserted = word(60f, 100f, 3)
        // Cross the "t" of the first word after everything else.
        val cross = StrokeBox(nextIndex++, 9f, 97f, 22f, 99f, 900_000L)
        val lines = InkLines.cluster(first + third + inserted + cross)
        assertEquals(1, lines.size)
        val order = lines[0].order
        assertEquals(first.map { it.index } + cross.index, order.take(4))
        assertEquals(inserted.map { it.index }, order.subList(4, 7))
        assertEquals(third.map { it.index }, order.subList(7, 10))
    }

    @Test
    fun wordSplittingUsesTheGapThreshold() {
        val a = letter(0f, 10f)
        val b = letter(12f, 10f) // 2pt gap: same word
        val c = letter(40f, 10f) // 18pt gap: new word
        val words = InkLines.splitWords(listOf(c, a, b), gap = 6f)
        assertEquals(listOf(listOf(a.index, b.index), listOf(c.index)), words.map { w -> w.map { it.index } })
    }

    @Test
    fun medianHeightIgnoresSpecks() {
        val boxes = listOf(letter(0f, 0f, h = 10f), letter(0f, 0f, h = 12f), letter(0f, 0f, h = 14f), letter(0f, 0f, h = 0.5f))
        assertEquals(12f, InkLines.medianHeight(boxes))
    }
}
