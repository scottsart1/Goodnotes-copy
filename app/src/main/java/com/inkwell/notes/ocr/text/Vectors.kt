package com.inkwell.notes.ocr.text

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Embedding vectors as stored in the embeddings table (little-endian float32). */
object Vectors {
    fun encode(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in v) buf.putFloat(f)
        return buf.array()
    }

    /** Null when [bytes] is not a whole number of floats or holds non-finite values. */
    fun decode(bytes: ByteArray): FloatArray? {
        if (bytes.isEmpty() || bytes.size % 4 != 0) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        for (i in out.indices) {
            val f = buf.getFloat()
            if (!f.isFinite()) return null
            out[i] = f
        }
        return out
    }

    fun norm(v: FloatArray): Double {
        var s = 0.0
        for (f in v) s += f.toDouble() * f
        return sqrt(s)
    }

    /** Cosine similarity in -1..1; 0 for mismatched dimensions or zero vectors. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            na += x * x
            nb += y * y
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat().coerceIn(-1f, 1f)
    }

    /**
     * Cosine of [query] (with precomputed [queryNorm]) against an encoded vector, decoding
     * in place without allocating. 0 when dimensions differ.
     */
    fun cosineEncoded(query: FloatArray, queryNorm: Double, encoded: ByteArray): Float {
        if (encoded.size != query.size * 4 || queryNorm == 0.0) return 0f
        val buf = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        var dot = 0.0
        var nb = 0.0
        for (i in query.indices) {
            val y = buf.getFloat().toDouble()
            if (!y.isFinite()) return 0f
            dot += query[i] * y
            nb += y * y
        }
        if (nb == 0.0) return 0f
        return (dot / (queryNorm * sqrt(nb))).toFloat().coerceIn(-1f, 1f)
    }
}
