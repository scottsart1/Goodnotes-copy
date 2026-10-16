package com.inkwell.notes.ink.select

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Immutable 2D affine transform in page points: x' = a x + c y + tx, y' = b x + d y + ty (the same
 * layout as the viewport and page-rotation affines). Positive rotation angles turn clockwise in
 * the y-down page space, matching Canvas.rotate and the item model's rotation fields. Pure Kotlin.
 */
data class Affine(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val tx: Float = 0f,
    val ty: Float = 0f,
) {
    val det: Float get() = a * d - b * c

    /** Geometric mean scale (area scale square root): how much widths grow. */
    val meanScale: Float get() = sqrt(abs(det))

    val isIdentity: Boolean
        get() = a == 1f && b == 0f && c == 0f && d == 1f && tx == 0f && ty == 0f

    /** True when only a translation (no scale, rotation or shear). */
    val isTranslation: Boolean get() = a == 1f && b == 0f && c == 0f && d == 1f

    fun mapX(x: Float, y: Float): Float = a * x + c * y + tx
    fun mapY(x: Float, y: Float): Float = b * x + d * y + ty

    /** Maps x,y pairs of [pts] in place, [stride] floats per sample (x, y first). */
    fun mapPoints(pts: FloatArray, stride: Int = 2) {
        var i = 0
        while (i + 1 < pts.size) {
            val x = pts[i]; val y = pts[i + 1]
            pts[i] = a * x + c * y + tx
            pts[i + 1] = b * x + d * y + ty
            i += stride
        }
    }

    /** Applies this transform first, then [next]. */
    fun then(next: Affine): Affine = Affine(
        a = next.a * a + next.c * b,
        b = next.b * a + next.d * b,
        c = next.a * c + next.c * d,
        d = next.b * c + next.d * d,
        tx = next.a * tx + next.c * ty + next.tx,
        ty = next.b * tx + next.d * ty + next.ty,
    )

    fun inverse(): Affine? {
        val det = det
        if (abs(det) < 1e-12f) return null
        val ia = d / det; val ib = -b / det; val ic = -c / det; val id = a / det
        return Affine(ia, ib, ic, id, -(ia * tx + ic * ty), -(ib * tx + id * ty))
    }

    /** Values in android.graphics.Matrix order (MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y, 0, 0, 1). */
    fun toMatrixValues(out: FloatArray = FloatArray(9)): FloatArray {
        out[0] = a; out[1] = c; out[2] = tx
        out[3] = b; out[4] = d; out[5] = ty
        out[6] = 0f; out[7] = 0f; out[8] = 1f
        return out
    }

    companion object {
        val IDENTITY = Affine()

        fun translate(dx: Float, dy: Float) = Affine(tx = dx, ty = dy)

        /** Scales by ([sx], [sy]) keeping the point ([px], [py]) fixed. */
        fun scale(sx: Float, sy: Float, px: Float, py: Float) = Affine(a = sx, d = sy, tx = px - sx * px, ty = py - sy * py)

        /** Rotates by [radians] (clockwise in y-down space) around ([px], [py]). */
        fun rotate(radians: Float, px: Float, py: Float): Affine {
            val cs = cos(radians); val sn = sin(radians)
            return Affine(a = cs, b = sn, c = -sn, d = cs, tx = px - cs * px + sn * py, ty = py - sn * px - cs * py)
        }

        /** From an affine array [a, b, c, d, tx, ty] as used by the viewport and page rotation. */
        fun of(v: FloatArray) = Affine(v[0], v[1], v[2], v[3], v[4], v[5])
    }
}
