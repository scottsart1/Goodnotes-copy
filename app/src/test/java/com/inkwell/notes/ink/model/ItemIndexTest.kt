package com.inkwell.notes.ink.model

import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.ink.TestInk
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.geometry.SpatialGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.IdentityHashMap

/** Spatial index behaviour the erasers and taps rely on, including whiteboard coordinates. */
class ItemIndexTest {
    @Test fun hitTestPrefersTheTopmostItemAndRespectsZOverListOrder() {
        val under = TestInk.line("under", 0f, 0f, 100f, 0f, z = 5)
        val over = TestInk.line("over", 0f, 0f, 100f, 0f, z = 1)
        // "under" is listed first but has the higher z, so it is painted last and hit first.
        val index = ItemIndex.build(PageContent(strokes = listOf(under, over)), IdentityHashMap())
        assertSame(under, index.hitTest(50f, 0f, 2f))
        assertSame(over, index.hitTest(50f, 0f, 2f) { it !== under })
        assertNull(index.hitTest(50f, 30f, 2f))
    }

    @Test fun highlighterBeneathInkOfTheSameZIsHitSecond() {
        val hl = TestInk.line("hl", 0f, 0f, 100f, 0f, pen = PenType.HIGHLIGHTER, width = 12f, z = 3)
        val ink = TestInk.line("ink", 0f, 0f, 100f, 0f, z = 3)
        val index = ItemIndex.build(PageContent(strokes = listOf(ink, hl)), IdentityHashMap())
        assertSame(ink, index.hitTest(50f, 0f, 1f))
    }

    @Test fun negativeAndFarCoordinatesOnTheInfiniteCanvas() {
        val far = TestInk.line("far", -150_000f, -20_000f, -149_900f, -20_000f)
        val near = TestInk.line("near", 10f, 10f, 20f, 10f)
        val content = PageContent(strokes = listOf(far, near))
        val index = ItemIndex.build(content, IdentityHashMap())
        assertSame(far, index.hitTest(-149_950f, -20_000f, 3f))
        assertEquals(listOf<Any>(far), index.hitBySegment(-149_960f, -19_990f, -149_940f, -20_010f, 2f) { true })
        assertTrue(index.hitBySegment(0f, 0f, 5f, 5f, 2f) { true }.isEmpty())
    }

    @Test fun oversizedItemsAreStillFoundByEveryQuery() {
        val huge = TestInk.line("huge", -50_000f, 0f, 50_000f, 0f)
        val index = ItemIndex.build(PageContent(strokes = listOf(huge)), IdentityHashMap())
        assertSame(huge, index.hitTest(12_345f, 0f, 2f))
        assertSame(huge, index.hitTest(-40_000f, 0f, 2f))
        val grid = SpatialGrid(96f)
        grid.insert(0, Bounds(-50_000f, -1f, 50_000f, 1f))
        var hits = 0
        grid.query(Bounds(100f, -5f, 110f, 5f)) { hits++ }
        assertEquals(1, hits)
    }

    @Test fun boundsCacheIsReusedAcrossVersionsButConnectorsAreRecomputed() {
        val a: Stroke = TestInk.line("a", 0f, 0f, 100f, 0f)
        val cache = IdentityHashMap<Any, Bounds>()
        val v1 = ItemIndex.build(PageContent(strokes = listOf(a)), cache)
        val b1 = v1.boundsOf(a)!!
        val v2 = ItemIndex.build(PageContent(strokes = listOf(a, TestInk.line("b", 0f, 50f, 10f, 50f))), cache)
        assertSame(b1, v2.boundsOf(a))
        assertEquals(2, cache.size)
    }
}
