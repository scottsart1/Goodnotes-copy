package com.inkwell.notes.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSizingTest {

    @Test fun smallImagesKeepTheirSize() {
        assertEquals(PixelSize(1200, 1600), ImageSizing.downscale(1200, 1600))
        assertEquals(PixelSize(2480, 2480), ImageSizing.downscale(2480, 2480))
    }

    @Test fun photosAreDownscaledToTheLongestSide() {
        assertEquals(PixelSize(1860, 2480), ImageSizing.downscale(3000, 4000))
        assertEquals(PixelSize(2480, 1860), ImageSizing.downscale(4000, 3000))
        assertEquals(PixelSize(2480, 1395), ImageSizing.downscale(8000, 4500))
    }

    @Test fun extremeAspectRatiosNeverReachZero() {
        val panorama = ImageSizing.downscale(40_000, 10)
        assertEquals(2480, panorama.width)
        assertEquals(1, panorama.height)
        val tall = ImageSizing.downscale(1080, 30_000)
        assertEquals(2480, tall.height)
        assertEquals(89, tall.width)
    }

    @Test fun customLimit() {
        assertEquals(PixelSize(1000, 750), ImageSizing.downscale(4000, 3000, maxSide = 1000))
    }

    @Test fun pageIsA4WideWithTheImageAspect() {
        val (w, h) = ImageSizing.pageSizeFor(2480, 3508)
        assertEquals(595f, w, 0.01f)
        assertEquals(841.7f, h, 0.1f)
        val (lw, lh) = ImageSizing.pageSizeFor(4000, 3000)
        assertEquals(595f, lw, 0.01f)
        assertEquals(446.25f, lh, 0.01f)
    }

    @Test fun veryTallScreenshotsFitTheMaximumPageHeight() {
        val (w, h) = ImageSizing.pageSizeFor(1080, 60_000)
        assertEquals(ImageSizing.MAX_PAGE_PT, h, 0.01f)
        assertEquals(1080f / 60_000f, w / h, 0.0001f)
    }

    @Test fun degenerateSizesGetAnA4Page() {
        val (w, h) = ImageSizing.pageSizeFor(0, 0)
        assertEquals(595f, w, 0.01f)
        assertEquals(841.4f, h, 0.1f)
        val (_, thin) = ImageSizing.pageSizeFor(100_000, 1)
        assertTrue(thin >= ImageSizing.MIN_PAGE_PT)
    }

    @Test fun photosUseJpegAndScreenshotsStayLossless() {
        assertTrue(ImageSizing.preferJpeg("image/jpeg"))
        assertTrue(ImageSizing.preferJpeg("IMAGE/HEIC"))
        assertTrue(ImageSizing.preferJpeg("image/webp"))
        assertFalse(ImageSizing.preferJpeg("image/png"))
        assertFalse(ImageSizing.preferJpeg("image/gif"))
        assertFalse(ImageSizing.preferJpeg(null))
    }
}
