package com.inkwell.notes.export.format

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 2D affine transform in PDF order: (x, y) -> (a·x + c·y + e, b·x + d·y + f).
 */
data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
    fun mapX(x: Float, y: Float): Float = a * x + c * y + e
    fun mapY(x: Float, y: Float): Float = b * x + d * y + f

    /** The transform that applies [inner] first, then this one. */
    fun after(inner: Affine): Affine = Affine(
        a = a * inner.a + c * inner.b,
        b = b * inner.a + d * inner.b,
        c = a * inner.c + c * inner.d,
        d = b * inner.c + d * inner.d,
        e = a * inner.e + c * inner.f + e,
        f = b * inner.e + d * inner.f + f,
    )

    /** Maps packed x,y pairs. */
    fun mapPoints(xy: FloatArray): FloatArray {
        val out = FloatArray(xy.size)
        var i = 0
        while (i + 1 < xy.size) {
            out[i] = mapX(xy[i], xy[i + 1])
            out[i + 1] = mapY(xy[i], xy[i + 1])
            i += 2
        }
        return out
    }

    /** left, bottom, right, top (PDF rectangle) of the mapped rectangle. */
    fun mapRect(left: Float, top: Float, right: Float, bottom: Float): FloatArray {
        val pts = mapPoints(floatArrayOf(left, top, right, top, right, bottom, left, bottom))
        return boundsOf(pts)
    }

    companion object {
        val IDENTITY = Affine(1f, 0f, 0f, 1f, 0f, 0f)
        fun translate(tx: Float, ty: Float) = Affine(1f, 0f, 0f, 1f, tx, ty)
        fun scale(sx: Float, sy: Float = sx) = Affine(sx, 0f, 0f, sy, 0f, 0f)

        /**
         * Rotation by [degrees] clockwise as seen on a y-down page (Android's Canvas.rotate),
         * around (cx, cy).
         */
        fun rotation(degrees: Float, cx: Float, cy: Float): Affine {
            val r = Math.toRadians(degrees.toDouble())
            val cs = cos(r).toFloat()
            val sn = sin(r).toFloat()
            return Affine(cs, sn, -sn, cs, cx - cs * cx + sn * cy, cy - sn * cx - cs * cy)
        }

        /** left, bottom, right, top of packed x,y points. */
        fun boundsOf(xy: FloatArray): FloatArray {
            var l = Float.MAX_VALUE; var b = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var t = -Float.MAX_VALUE
            var i = 0
            while (i + 1 < xy.size) {
                l = min(l, xy[i]); r = max(r, xy[i]); b = min(b, xy[i + 1]); t = max(t, xy[i + 1])
                i += 2
            }
            return floatArrayOf(l, b, r, t)
        }
    }
}

/**
 * Coordinate mapping between Inkwell page points (origin top-left of the unrotated page,
 * y down) and PDF user space (origin bottom-left, y up).
 */
object PdfGeometry {
    /** PDF pages may be at most 14 400 units on a side (PDF 1.7, 200 inches). */
    const val MAX_PAGE_SIDE = 14_400f

    fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360 / 90 * 90

    /**
     * Maps page points into the user space of a PDF page whose visible box is
     * [llx], [lly], [urx], [ury] and which carries /Rotate [boxRotation]. Page points are
     * measured on the page as a viewer shows it after that rotation, so annotations of an
     * imported PDF page line up with what the editor showed. [pageWidthPt] x [pageHeightPt]
     * is Inkwell's size of the page; when it differs from the displayed box size the
     * content is scaled to fit.
     */
    fun displayToUser(
        llx: Float, lly: Float, urx: Float, ury: Float,
        boxRotation: Int,
        pageWidthPt: Float,
        pageHeightPt: Float,
    ): Affine {
        val rot = normalizeRotation(boxRotation)
        val bw = urx - llx
        val bh = ury - lly
        val displayW = if (rot == 90 || rot == 270) bh else bw
        val displayH = if (rot == 90 || rot == 270) bw else bh
        val sx = if (pageWidthPt > 0f) displayW / pageWidthPt else 1f
        val sy = if (pageHeightPt > 0f) displayH / pageHeightPt else 1f
        val r = when (rot) {
            90 -> Affine(0f, 1f, 1f, 0f, llx, lly)
            180 -> Affine(-1f, 0f, 0f, 1f, urx, lly)
            270 -> Affine(0f, -1f, -1f, 0f, urx, ury)
            else -> Affine(1f, 0f, 0f, -1f, llx, ury)
        }
        return r.after(Affine.scale(sx, sy))
    }

    /** Page points of a new [widthPt] x [heightPt] page (MediaBox at the origin) to user space. */
    fun pageToUser(widthPt: Float, heightPt: Float): Affine = Affine(1f, 0f, 0f, -1f, 0f, heightPt)

    /**
     * Size and transform of the PDF page for an unbounded (whiteboard) page whose content
     * spans [left]..[right] x [top]..[bottom]: a [margin] around it, scaled down when it
     * would exceed [MAX_PAGE_SIDE]. Returns (width, height, transform from page points).
     */
    fun boardPage(left: Float, top: Float, right: Float, bottom: Float, margin: Float): Triple<Float, Float, Affine> {
        val w = (right - left) + 2 * margin
        val h = (bottom - top) + 2 * margin
        val s = min(1f, MAX_PAGE_SIDE / max(w, h).coerceAtLeast(1f))
        val pw = (w * s).coerceAtLeast(1f)
        val ph = (h * s).coerceAtLeast(1f)
        val toPage = Affine.scale(s).after(Affine.translate(margin - left, margin - top))
        return Triple(pw, ph, pageToUser(pw, ph).after(toPage))
    }

    /** The /Rotate of an exported page: the source page's own rotation plus Inkwell's. */
    fun combinedRotation(sourceRotation: Int, pageRotation: Int): Int = normalizeRotation(sourceRotation + pageRotation)

    /** True when [a] maps into a mirrored (y-flipped) space, so glyphs need a counter-flip. */
    fun isMirrored(a: Affine): Boolean = a.a * a.d - a.b * a.c < 0f

    /** Floats close enough to be the same rendered position. */
    fun near(x: Float, y: Float, eps: Float = 1e-3f): Boolean = abs(x - y) <= eps
}

/** ARGB colour components for PDF operators. */
object PdfColor {
    fun red(argb: Int): Float = ((argb shr 16) and 0xFF) / 255f
    fun green(argb: Int): Float = ((argb shr 8) and 0xFF) / 255f
    fun blue(argb: Int): Float = (argb and 0xFF) / 255f
    fun alpha(argb: Int): Float = ((argb ushr 24) and 0xFF) / 255f
}
