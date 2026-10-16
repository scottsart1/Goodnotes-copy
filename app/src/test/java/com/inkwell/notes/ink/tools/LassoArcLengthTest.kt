package com.inkwell.notes.ink.tools

import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.LassoFilter
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.ink.TestInk
import com.inkwell.notes.ink.geometry.ItemGeometry
import com.inkwell.notes.ink.model.ItemIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.IdentityHashMap

/**
 * Lasso containment judges a stroke by how much of its ink lies inside the loop, not by how many
 * raw samples do: a pen that lingers at the start of a stroke produces many samples in one spot.
 */
class LassoArcLengthTest {
    private fun stroke(id: String, xs: List<Float>, y: Float): Stroke {
        val p = FloatArray(xs.size * InkPoints.STRIDE)
        for (i in xs.indices) {
            p[i * InkPoints.STRIDE] = xs[i]; p[i * InkPoints.STRIDE + 1] = y
            p[i * InkPoints.STRIDE + InkPoints.PRESSURE] = 0.5f; p[i * InkPoints.STRIDE + InkPoints.TIME] = i * 8f
        }
        return Stroke(id, PenType.BALLPOINT, 0xFF000000.toInt(), 2f, p, 0L)
    }

    private fun select(c: PageContent, poly: FloatArray): Set<String> =
        LassoSelector.select(c, ItemIndex.build(c, IdentityHashMap()), poly, LassoFilter())

    @Test fun strokeWrittenSlowlyAtOneEndIsSelectedByItsInkNotItsSampleCount() {
        // 60 samples crawl through x 0..10, then 20 samples race through x 10..100: the right 88% of
        // the ink is inside the loop but only a quarter of the samples are.
        val xs = (0 until 60).map { it * 10f / 59f } + (1..20).map { 10f + it * 90f / 20f }
        val s = stroke("s", xs, 50f)
        val c = PageContent(strokes = listOf(s))
        assertEquals(setOf("s"), select(c, LassoSelector.rectPolygon(12f, 0f, 120f, 100f)))
        // The mirror image: the loop around the slow start covers 12% of the ink and must not select.
        assertEquals(emptySet<String>(), select(c, LassoSelector.rectPolygon(-10f, 0f, 12f, 100f)))
    }

    @Test fun evenlySampledStrokesKeepTheSixtyPercentRule() {
        val in55 = TestInk.line("s55", -44f, 50f, 56f, 50f)
        val in65 = TestInk.line("s65", -34f, 30f, 66f, 30f)
        val c = PageContent(strokes = listOf(in55, in65))
        assertEquals(setOf("s65"), select(c, LassoSelector.rectPolygon(0f, 0f, 100f, 100f)))
    }

    @Test fun strokeSamplesAreEvenlySpacedAndKeepBothEnds() {
        val s = stroke("s", (0 until 100).map { if (it < 90) it * 0.1f else 9f + (it - 89) * 10f }, 0f)
        val out = ItemGeometry.strokeSamples(s, 11)
        assertEquals(22, out.size)
        assertEquals(0f, out[0], 1e-4f)
        assertEquals(109f, out[20], 1e-3f)
        // 11 samples over 109pt: about 10.9pt apart.
        for (k in 1 until 11) assertEquals(k * 10.9f, out[k * 2], 0.05f)
        // Never more samples than the stroke has.
        assertEquals(3 * 2, ItemGeometry.strokeSamples(TestInk.stroke("t", 0f, 0f, 10f, 0f, 20f, 0f), 48).size)
    }

    @Test fun degenerateStrokesGiveASinglePoint() {
        assertEquals(0, ItemGeometry.strokeSamples(Stroke("e", PenType.BALLPOINT, 0, 2f, FloatArray(0), 0L)).size)
        val dot = ItemGeometry.strokeSamples(TestInk.stroke("d", 5f, 6f))
        assertEquals(listOf(5f, 6f), dot.toList())
        val dup = ItemGeometry.strokeSamples(TestInk.stroke("p", 5f, 6f, 5f, 6f, 5f, 6f))
        assertEquals(listOf(5f, 6f), dup.toList())
        // A dot is selected by where it is.
        val c = PageContent(strokes = listOf(TestInk.stroke("d", 5f, 6f)))
        assertEquals(setOf("d"), select(c, LassoSelector.rectPolygon(0f, 0f, 10f, 10f)))
        assertTrue(select(c, LassoSelector.rectPolygon(20f, 20f, 30f, 30f)).isEmpty())
    }
}
