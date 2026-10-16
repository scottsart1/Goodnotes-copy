package com.inkwell.notes.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderSizingTest {

    @Test fun smallWidthsRoundToSixteen() {
        assertEquals(16, RenderSizing.widthBucket(1))
        assertEquals(16, RenderSizing.widthBucket(16))
        assertEquals(32, RenderSizing.widthBucket(17))
        assertEquals(240, RenderSizing.widthBucket(240))
        assertEquals(256, RenderSizing.widthBucket(241))
        assertEquals(256, RenderSizing.widthBucket(256))
    }

    @Test fun largerWidthsRoundToAnEighthOfTheirPowerOfTwo() {
        assertEquals(288, RenderSizing.widthBucket(257))
        assertEquals(1024, RenderSizing.widthBucket(1000))
        assertEquals(1152, RenderSizing.widthBucket(1025))
        assertEquals(2048, RenderSizing.widthBucket(2048))
        assertEquals(2304, RenderSizing.widthBucket(2049))
        assertEquals(4608, RenderSizing.widthBucket(4352))
    }

    @Test fun multiplesOf256StayExact() {
        // The ink layer asks for widths rounded to 256 px; those must not be inflated further.
        for (w in (256..4096 step 256)) assertEquals(w, RenderSizing.widthBucket(w))
    }

    @Test fun bucketsNeverShrinkAndOvershootAtMostAnEighth() {
        for (w in 1..9000) {
            val b = RenderSizing.widthBucket(w)
            assertTrue("bucket($w)=$b", b >= w)
            if (w > 256) assertTrue("bucket($w)=$b overshoots", b - w < w / 8 + 1)
        }
        assertEquals(0, RenderSizing.widthBucket(0))
        assertEquals(0, RenderSizing.widthBucket(-5))
    }

    @Test fun nearbyWidthsShareACacheKey() {
        val a = RenderKey("doc", 3, RenderSizing.target(1001, 1415)!!.width)
        val b = RenderKey("doc", 3, RenderSizing.target(1024, 1448)!!.width)
        val c = RenderKey("doc", 3, RenderSizing.target(1030, 1456)!!.width)
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertNotEquals(a, RenderKey("doc", 4, a.widthBucket))
        assertNotEquals(a, RenderKey("other", 3, a.widthBucket))
    }

    @Test fun targetKeepsTheRequestedAspect() {
        val t = RenderSizing.target(1000, 1414)!!
        assertEquals(1024, t.width)
        assertEquals(1448, t.height)
        assertNull(RenderSizing.target(0, 100))
        assertNull(RenderSizing.target(100, 0))
    }

    @Test fun hugeRequestsAreCapped() {
        val t = RenderSizing.target(12_000, 16_970)!!
        assertTrue(t.width <= RenderSizing.MAX_SIDE && t.height <= RenderSizing.MAX_SIDE)
        assertTrue(t.pixels <= RenderSizing.MAX_PIXELS)
        assertEquals(16_970.0 / 12_000, t.height.toDouble() / t.width, 0.01)

        val wide = RenderSizing.cap(PixelSize(20_000, 100))
        assertEquals(RenderSizing.MAX_SIDE, wide.width)
        assertTrue(wide.height >= 1)
    }

    @Test fun normalRequestsAreNotCapped() {
        val s = PixelSize(3072, 4344)
        assertEquals(s, RenderSizing.cap(s))
    }

    @Test fun cachedBitmapMustMatchWidthAndShape() {
        val t = PixelSize(1024, 1448)
        assertTrue(RenderSizing.matches(1024, 1448, t))
        assertTrue(RenderSizing.matches(1024, 1450, t))
        assertFalse(RenderSizing.matches(1024, 724, t)) // a rotated or different page shape
        assertFalse(RenderSizing.matches(1152, 1448, t))
    }

    @Test fun cacheBudgetIsAnEighthOfTheHeapWithinBounds() {
        val mb = 1024L * 1024
        assertEquals(64 * mb, RenderSizing.cacheBudgetBytes(512 * mb))
        assertEquals(16 * mb, RenderSizing.cacheBudgetBytes(64 * mb))
        assertEquals(256 * mb, RenderSizing.cacheBudgetBytes(8192 * mb))
    }
}
