package com.inkwell.notes.ocr

import com.inkwell.notes.ocr.text.Bounds
import com.inkwell.notes.ocr.text.ImagePlacement
import com.inkwell.notes.ocr.text.OcrScaling
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundsAndScalingTest {
    @Test
    fun formatsCompactlyAndParsesBack() {
        val s = Bounds.format(floatArrayOf(10f, 20.25f, 110.5f, 40f))
        assertEquals("10,20.3,110.5,40", s)
        assertArrayEquals(floatArrayOf(10f, 20.3f, 110.5f, 40f), Bounds.parse(s)!!, 1e-4f)
    }

    @Test
    fun parseToleratesSpacesAndNormalisesSwappedEdges() {
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), Bounds.parse(" 3, 4 ,1,2 ")!!, 0f)
        assertArrayEquals(floatArrayOf(-5f, -1f, 5f, 1f), Bounds.parse("-5,-1,5,1")!!, 0f)
    }

    @Test
    fun malformedBoundsAreNull() {
        assertNull(Bounds.parse(null))
        assertNull(Bounds.parse(""))
        assertNull(Bounds.parse("1,2,3"))
        assertNull(Bounds.parse("1,2,3,4,5"))
        assertNull(Bounds.parse("a,b,c,d"))
        assertNull(Bounds.parse("1,NaN,3,4"))
        assertNull(Bounds.parse("1,Infinity,3,4"))
        assertNull(Bounds.format(null))
        assertNull(Bounds.format(floatArrayOf(1f, 2f)))
        assertNull(Bounds.format(floatArrayOf(1f, Float.NaN, 2f, 3f)))
    }

    @Test
    fun unionOfBoxes() {
        assertArrayEquals(floatArrayOf(0f, 1f, 10f, 12f), Bounds.union(listOf(floatArrayOf(0f, 5f, 4f, 12f), floatArrayOf(2f, 1f, 10f, 3f)))!!, 0f)
        assertNull(Bounds.union(emptyList()))
    }

    @Test
    fun imageTextMapsThroughPlacementAndCrop() {
        val item = floatArrayOf(100f, 200f, 300f, 150f)
        val noCrop = floatArrayOf(0f, 0f, 0f, 0f)
        assertArrayEquals(
            floatArrayOf(160f, 230f, 280f, 260f),
            ImagePlacement.toPage(floatArrayOf(0.2f, 0.2f, 0.6f, 0.4f), item, noCrop, 0f)!!,
            1e-3f,
        )
        // Left half cropped away: x 0.5..1 of the source fills the item.
        val crop = floatArrayOf(0.5f, 0f, 0f, 0f)
        assertArrayEquals(
            floatArrayOf(100f, 200f, 220f, 350f),
            ImagePlacement.toPage(floatArrayOf(0.4f, 0f, 0.7f, 1f), item, crop, 0f)!!,
            1e-3f,
        )
        // Entirely in the cropped part.
        assertNull(ImagePlacement.toPage(floatArrayOf(0.1f, 0.1f, 0.3f, 0.3f), item, crop, 0f))
        // Rotated images use the whole item.
        assertArrayEquals(floatArrayOf(100f, 200f, 400f, 350f), ImagePlacement.toPage(floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f), item, noCrop, 90f)!!, 0f)
    }

    @Test
    fun ocrDownscalesToTheMaximumEdge() {
        assertEquals(1f, OcrScaling.scaleFor(2000, 1000))
        assertEquals(0.5f, OcrScaling.scaleFor(5000, 3000), 1e-6f)
        assertEquals(2500 to 1500, OcrScaling.scaledSize(5000, 3000))
        assertEquals(1000 to 800, OcrScaling.scaledSize(1000, 800))
    }

    @Test
    fun decodeSubsamplingNeverGoesBelowTheMaximumEdge() {
        assertEquals(1, OcrScaling.sampleSize(4000, 3000))
        assertEquals(2, OcrScaling.sampleSize(6000, 4000))
        assertEquals(4, OcrScaling.sampleSize(12000, 9000))
        assertEquals(1, OcrScaling.sampleSize(100, 100))
    }

    @Test
    fun pdfPagesRenderAt200DpiWithinTheCap() {
        // A4 at 200 dpi.
        assertEquals(1653 to 2339, OcrScaling.renderSize(595f, 842f))
        // A3 would exceed 2500 px: capped, aspect kept.
        val (w, h) = OcrScaling.renderSize(842f, 1191f)
        assertEquals(2500, h)
        assertEquals(1767, w)
    }

    @Test
    fun boxesMapBetweenPixelSpaces() {
        assertArrayEquals(
            floatArrayOf(59.5f, 84.2f, 119f, 168.4f),
            OcrScaling.mapBox(250f, 250f, 500f, 500f, 2500, 2500, 595f, 842f),
            0.1f,
        )
    }
}
