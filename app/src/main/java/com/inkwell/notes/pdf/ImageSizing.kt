package com.inkwell.notes.pdf

import kotlin.math.roundToInt

/** Pure sizing rules for turning photos, scans and screenshots into PDF pages. */
internal object ImageSizing {
    /** Longest side of an embedded image: 2480 px is A4 at 300 dpi. */
    const val MAX_IMAGE_SIDE = 2480

    /** A4 width in points (72 per inch). */
    const val PAGE_WIDTH_PT = 595f

    /** Largest page side PDF viewers accept (200 inches). */
    const val MAX_PAGE_PT = 14_400f

    /** Smallest page side the PDF format allows. */
    const val MIN_PAGE_PT = 3f

    const val JPEG_QUALITY = 0.85f

    /** Decode size so the longest side is at most [maxSide], keeping the aspect ratio. */
    fun downscale(width: Int, height: Int, maxSide: Int = MAX_IMAGE_SIDE): PixelSize {
        if (width <= 0 || height <= 0) return PixelSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
        val longest = maxOf(width, height)
        if (longest <= maxSide) return PixelSize(width, height)
        val s = maxSide.toDouble() / longest
        return PixelSize(
            (width * s).roundToInt().coerceIn(1, maxSide),
            (height * s).roundToInt().coerceIn(1, maxSide),
        )
    }

    /**
     * Page size in points for an image: A4 width with the image's aspect ratio. Very tall images
     * (long screenshots) are fitted to [MAX_PAGE_PT] high instead.
     */
    fun pageSizeFor(width: Int, height: Int): Pair<Float, Float> {
        if (width <= 0 || height <= 0) return PAGE_WIDTH_PT to PAGE_WIDTH_PT * 297f / 210f
        var w = PAGE_WIDTH_PT
        var h = PAGE_WIDTH_PT * height / width
        if (h > MAX_PAGE_PT) {
            h = MAX_PAGE_PT
            w = MAX_PAGE_PT * width / height
        }
        return w.coerceIn(MIN_PAGE_PT, MAX_PAGE_PT) to h.coerceIn(MIN_PAGE_PT, MAX_PAGE_PT)
    }

    /**
     * Photos (JPEG, HEIF, AVIF, WebP) keep JPEG compression so files stay small; screenshots,
     * PNG scans and other lossless sources stay lossless so text stays crisp.
     */
    fun preferJpeg(mimeType: String?): Boolean = when (mimeType?.lowercase()) {
        "image/jpeg", "image/jpg", "image/pjpeg", "image/heic", "image/heif", "image/avif", "image/webp",
        "image/x-adobe-dng", "image/dng" -> true
        else -> false
    }
}
