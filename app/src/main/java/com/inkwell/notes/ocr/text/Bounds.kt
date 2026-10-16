package com.inkwell.notes.ocr.text

import java.util.Locale

/** The "left,top,right,bottom" bounds column of the text index. */
object Bounds {
    /** Encodes [b] (left, top, right, bottom) or returns null when it is not a usable box. */
    fun format(b: FloatArray?): String? {
        if (b == null || b.size != 4 || b.any { !it.isFinite() }) return null
        val l = minOf(b[0], b[2])
        val r = maxOf(b[0], b[2])
        val t = minOf(b[1], b[3])
        val bo = maxOf(b[1], b[3])
        return listOf(l, t, r, bo).joinToString(",") { num(it) }
    }

    /** Parses "l,t,r,b"; null when missing or malformed. Swapped edges are normalised. */
    fun parse(s: String?): FloatArray? {
        if (s.isNullOrBlank()) return null
        val parts = s.split(',')
        if (parts.size != 4) return null
        val v = FloatArray(4)
        for (i in 0 until 4) {
            v[i] = parts[i].trim().toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
        }
        return floatArrayOf(minOf(v[0], v[2]), minOf(v[1], v[3]), maxOf(v[0], v[2]), maxOf(v[1], v[3]))
    }

    /** Smallest box containing all [boxes]; null for an empty list. */
    fun union(boxes: List<FloatArray>): FloatArray? {
        if (boxes.isEmpty()) return null
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        for (x in boxes) {
            if (x.size != 4) continue
            l = minOf(l, x[0]); t = minOf(t, x[1]); r = maxOf(r, x[2]); b = maxOf(b, x[3])
        }
        return if (l.isFinite() && t.isFinite() && r.isFinite() && b.isFinite()) floatArrayOf(l, t, r, b) else null
    }

    private fun num(f: Float): String {
        val rounded = Math.round(f * 10f) / 10f
        return if (rounded == rounded.toLong().toFloat()) rounded.toLong().toString()
        else String.format(Locale.US, "%.1f", rounded)
    }
}

/**
 * Maps text found in an image (normalised 0..1 coordinates of the source image) onto the page,
 * honouring the image item's placement and crop. Rotated images fall back to the whole item.
 */
object ImagePlacement {
    /**
     * @param box normalised l,t,r,b in the source image.
     * @param item x, y, width, height of the image item on the page.
     * @param crop left, top, right, bottom crop insets (fractions of the source).
     * @return page-point bounds, or null when the box lies entirely in the cropped-away area.
     */
    fun toPage(box: FloatArray, item: FloatArray, crop: FloatArray, rotationDeg: Float): FloatArray? {
        val (x, y, w, h) = item.toList()
        val whole = floatArrayOf(x, y, x + w, y + h)
        if (rotationDeg % 360f != 0f) return whole
        val cl = crop[0].coerceIn(0f, 1f)
        val ct = crop[1].coerceIn(0f, 1f)
        val cr = crop[2].coerceIn(0f, 1f)
        val cb = crop[3].coerceIn(0f, 1f)
        val visW = 1f - cl - cr
        val visH = 1f - ct - cb
        if (visW <= 0.001f || visH <= 0.001f) return null
        val l = ((box[0] - cl) / visW).coerceIn(0f, 1f)
        val r = ((box[2] - cl) / visW).coerceIn(0f, 1f)
        val t = ((box[1] - ct) / visH).coerceIn(0f, 1f)
        val b = ((box[3] - ct) / visH).coerceIn(0f, 1f)
        if (r - l <= 0f || b - t <= 0f) return null
        return floatArrayOf(x + l * w, y + t * h, x + r * w, y + b * h)
    }
}
