package com.inkwell.notes.ink.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Word clustering on handwriting with realistic letter proportions: x-height 8 pt, ascenders
 * 8 pt above it, descenders 10 pt below the baseline, ruled lines 20 pt apart.
 */
class WordClustererTest {
    private var t = 0L
    private fun box(id: String, l: Float, top: Float, r: Float, bottom: Float): WordClusterer.StrokeBox {
        t += 400
        return WordClusterer.StrokeBox(id, Bounds(l, top, r, bottom), t, t + 150)
    }

    // Line 1: baseline y = 20. x-height band 12..20, ascenders from 4, descenders to 30.
    private val d = box("d", 0f, 4f, 8f, 20f)
    private val o = box("o", 10f, 12f, 18f, 20f)
    private val g = box("g", 20f, 12f, 28f, 30f)
    // Line 2: baseline y = 40; "l" sits directly under "g", its ascender starting at 24.
    private val l2 = box("l2", 21f, 24f, 24f, 40f)
    private val a2 = box("a2", 26f, 32f, 34f, 40f)
    private val all = listOf(d, o, g, l2, a2)

    @Test fun aDescenderDoesNotPullInTheLetterOnTheLineBelow() {
        // The g's descender overlaps the l's ascender by 6 pt, as on any ruled page.
        assertEquals(setOf("d", "o", "g"), WordClusterer.wordOf("o", all))
        assertEquals(setOf("d", "o", "g"), WordClusterer.wordOf("g", all))
        assertEquals(setOf("l2", "a2"), WordClusterer.wordOf("l2", all))
        assertEquals(2, WordClusterer.words(all).size)
    }

    @Test fun ascenderAndDescenderLettersOfOneWordStayLinked() {
        // "p" (12..30) and "t" (4..20) share only the x-height band.
        val p = box("p", 0f, 12f, 8f, 30f)
        val tt = box("t", 10f, 4f, 14f, 20f)
        val h = WordClusterer.lineHeight(p, listOf(p, tt))
        assertTrue(WordClusterer.linked(p, tt, h))
        assertEquals(setOf("p", "t"), WordClusterer.wordOf("p", listOf(p, tt)))
    }

    @Test fun lettersStackedOnConsecutiveLinesAreNotLinked() {
        val h = WordClusterer.lineHeight(g, all)
        assertFalse(WordClusterer.linked(g, l2, h))
        assertFalse(WordClusterer.linked(l2, g, h))
    }
}
