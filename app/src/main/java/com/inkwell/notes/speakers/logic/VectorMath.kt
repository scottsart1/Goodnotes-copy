package com.inkwell.notes.speakers.logic

import kotlin.math.sqrt

/** Small float-vector helpers for speaker embeddings. Pure. */
object VectorMath {
    fun dot(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var acc = 0.0
        for (i in 0 until n) acc += a[i].toDouble() * b[i]
        return acc.toFloat()
    }

    fun norm(a: FloatArray): Float {
        var acc = 0.0
        for (x in a) acc += x.toDouble() * x
        return sqrt(acc).toFloat()
    }

    /** A unit-length copy; a zero (or non-finite) vector stays all zeros. */
    fun normalize(a: FloatArray): FloatArray {
        val n = norm(a)
        if (n <= 1e-12f || !n.isFinite()) return FloatArray(a.size)
        return FloatArray(a.size) { a[it] / n }
    }

    /** Cosine similarity in -1..1; 0 when either vector is zero or the sizes differ. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        val na = norm(a)
        val nb = norm(b)
        if (na <= 1e-12f || nb <= 1e-12f) return 0f
        return (dot(a, b) / (na * nb)).coerceIn(-1f, 1f)
    }

    /**
     * L2-normalised weighted mean of [vectors] (each normalised first, so loud or long inputs do
     * not dominate by magnitude). Non-positive weights are ignored. Null when nothing is left.
     */
    fun weightedMean(vectors: List<FloatArray>, weights: List<Double>): FloatArray? {
        if (vectors.isEmpty()) return null
        val dim = vectors.first().size
        val acc = DoubleArray(dim)
        var total = 0.0
        for ((i, v) in vectors.withIndex()) {
            val w = weights.getOrElse(i) { 1.0 }
            if (w <= 0.0 || v.size != dim) continue
            val u = normalize(v)
            for (k in 0 until dim) acc[k] += u[k] * w
            total += w
        }
        if (total <= 0.0) return null
        val out = FloatArray(dim) { acc[it].toFloat() }
        return if (norm(out) <= 1e-12f) null else normalize(out)
    }

    fun mean(vectors: List<FloatArray>): FloatArray? = weightedMean(vectors, List(vectors.size) { 1.0 })
}
