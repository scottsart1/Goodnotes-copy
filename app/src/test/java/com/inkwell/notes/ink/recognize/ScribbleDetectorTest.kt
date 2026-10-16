package com.inkwell.notes.ink.recognize

import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.ink.TestInk
import com.inkwell.notes.ink.model.ItemIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.IdentityHashMap

class ScribbleDetectorTest {
    /** Speed threshold used by the canvas (page points per ms) for these tests. */
    private val minSpeed = 0.6f

    private fun stroke(id: String, xy: FloatArray, pen: PenType = PenType.BALLPOINT): Stroke {
        val n = xy.size / 2
        val p = FloatArray(n * InkPoints.STRIDE)
        for (i in 0 until n) { p[i * InkPoints.STRIDE] = xy[i * 2]; p[i * InkPoints.STRIDE + 1] = xy[i * 2 + 1]; p[i * InkPoints.STRIDE + 2] = 0.5f }
        return Stroke(id, pen, 0xFF000000.toInt(), 1.6f, p, 0L)
    }

    /** A handwritten word ("min") sitting on y = 100 with an x-height of 20, from x = 0 to 75. */
    private fun word(): List<Stroke> = listOf(
        stroke("w1", SyntheticInk.cursive("m", 0f, 100f, 20f, seed = 1)),
        stroke("w2", SyntheticInk.cursive("n", 26f, 100f, 20f, seed = 2)),
        stroke("w3", SyntheticInk.cursive("m", 48f, 100f, 20f, seed = 3)),
    )

    private fun page(strokes: List<Stroke>): Pair<PageContent, ItemIndex> {
        val c = PageContent(strokes = strokes)
        return c to ItemIndex.build(c, IdentityHashMap())
    }

    /** Horizontal back-and-forth scratch over x 0..[w], drifting slowly downwards. */
    private fun scratch(x0: Float, y0: Float, w: Float, passes: Int, drift: Float, seed: Int = 1): FloatArray {
        val pts = ArrayList<Float>()
        for (k in 0..passes) {
            pts += if (k % 2 == 0) x0 else x0 + w
            pts += y0 + drift * k
        }
        return SyntheticInk(seed, jitter = 0.6f, wobble = 0.8f).polyline(*pts.toFloatArray(), step = 2f)
    }

    /** Vertical zig-zag progressing to the right over the word. */
    private fun zigzag(x0: Float, top: Float, bottom: Float, count: Int, spacing: Float): FloatArray {
        val pts = ArrayList<Float>()
        for (k in 0..count) { pts += x0 + k * spacing; pts += if (k % 2 == 0) top else bottom }
        return SyntheticInk(4, jitter = 0.6f, wobble = 0.6f).polyline(*pts.toFloatArray(), step = 2f)
    }

    private fun detect(xy: FloatArray, content: PageContent, index: ItemIndex, speed: Float = 1.5f): Set<String> {
        val a = ScribbleDetector.analyze(xy, SyntheticInk.times(xy, speed))
        if (!ScribbleDetector.looksLikeScribble(a, minSpeed)) return emptySet()
        return ScribbleDetector.coveredIds(content, index, xy, 1.6f)
    }

    @Test fun backAndForthScratchOverAWordErasesIt() {
        val (c, idx) = page(word())
        val xy = scratch(-4f, 82f, 84f, passes = 7, drift = 3f)
        val a = ScribbleDetector.analyze(xy, SyntheticInk.times(xy, 1.5f))
        assertTrue(a.toString(), a.reversals >= 6)
        assertEquals(setOf("w1", "w2", "w3"), detect(xy, c, idx))
    }

    @Test fun zigZagAcrossAWordErasesIt() {
        val (c, idx) = page(word())
        val xy = zigzag(-3f, 76f, 104f, count = 16, spacing = 5f)
        assertEquals(setOf("w1", "w2", "w3"), detect(xy, c, idx))
    }

    @Test fun scratchOnlyErasesWhatItCovers() {
        val other = stroke("far", SyntheticInk.cursive("e", 300f, 100f, 20f))
        val (c, idx) = page(word() + other)
        // Scratch only over the first letter.
        val xy = scratch(-3f, 82f, 24f, passes = 7, drift = 2.5f)
        val ids = detect(xy, c, idx)
        assertTrue(ids.toString(), "w1" in ids)
        assertFalse("far" in ids)
        assertFalse("w3" in ids)
    }

    @Test fun scratchOverEmptyPaperIsJustInk() {
        val (c, idx) = page(word())
        val xy = scratch(200f, 82f, 80f, passes = 7, drift = 3f)
        assertTrue(detect(xy, c, idx).isEmpty())
    }

    @Test fun slowZigZagIsNotAScribble() {
        val (c, idx) = page(word())
        val xy = scratch(-4f, 82f, 84f, passes = 7, drift = 3f)
        assertTrue(detect(xy, c, idx, speed = 0.2f).isEmpty())
    }

    @Test fun tooFewSwingsIsNotAScribble() {
        val (c, idx) = page(word())
        val xy = scratch(-4f, 82f, 84f, passes = 3, drift = 3f)
        assertTrue(detect(xy, c, idx).isEmpty())
    }

    @Test fun lettersAreNotScribbles() {
        // Normal handwriting written next to (not over) existing ink, at writing speed.
        val (c, idx) = page(word())
        for (w in listOf("m", "w", "mmm", "www", "nnn", "lel", "wenn")) {
            for (speed in listOf(0.4f, 1.2f)) {
                val xy = SyntheticInk.cursive(w, 100f, 100f, 20f, seed = w.length)
                assertTrue("'$w' at $speed", detect(xy, c, idx, speed).isEmpty())
            }
        }
    }

    @Test fun letterWrittenOverASpeckDoesNotEraseIt() {
        val dot = TestInk.stroke("dot", 110f, 90f, 111f, 90.5f)
        val (c, idx) = page(listOf(dot))
        val xy = SyntheticInk.cursive("mmm", 100f, 100f, 20f)
        assertTrue(detect(xy, c, idx).isEmpty())
    }

    @Test fun roundedLettersHaveLowSwingDensity() {
        // Loops and round letters spend much of their length curving rather than swinging.
        for (w in listOf("e", "eee", "lel")) {
            val xy = SyntheticInk.cursive(w, 0f, 100f, 20f)
            val a = ScribbleDetector.analyze(xy, null)
            assertFalse("'$w' $a", ScribbleDetector.looksLikeScribble(a, 0f))
        }
    }

    @Test fun highlightingIsNeverScribbledAway() {
        val hl = TestInk.line("hl", -2f, 90f, 80f, 90f, pen = PenType.HIGHLIGHTER, width = 14f)
        val (c, idx) = page(word() + hl)
        val xy = scratch(-4f, 82f, 84f, passes = 7, drift = 3f)
        val ids = detect(xy, c, idx)
        assertFalse("hl" in ids)
        assertTrue("w1" in ids)
    }

    @Test fun reversalsCountSwingsWithHysteresis() {
        // Small jitter must not count; full swings do.
        val xy = floatArrayOf(0f, 0f, 100f, 0f, 98f, 0f, 100f, 0f, 0f, 0f, 100f, 0f, 0f, 0f)
        assertEquals(3, ScribbleDetector.swings(xy, 1f, 0f).reversals)
    }
}
