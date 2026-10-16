package com.inkwell.notes.speakers.logic

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM conversions for decoded audio. Pure. */
object Pcm {
    /** Interleaved 16-bit frames to mono floats (-1..1), averaging the channels. */
    fun downmix16(pcm: ShortArray, length: Int, channels: Int): FloatArray {
        val ch = channels.coerceAtLeast(1)
        val frames = length / ch
        val out = FloatArray(frames)
        if (ch == 1) {
            for (i in 0 until frames) out[i] = pcm[i] / 32768f
            return out
        }
        for (f in 0 until frames) {
            var acc = 0
            val base = f * ch
            for (c in 0 until ch) acc += pcm[base + c]
            out[f] = acc / (32768f * ch)
        }
        return out
    }

    /** Interleaved float frames to mono, averaging the channels. */
    fun downmixFloat(pcm: FloatArray, length: Int, channels: Int): FloatArray {
        val ch = channels.coerceAtLeast(1)
        val frames = length / ch
        if (ch == 1) return pcm.copyOf(frames)
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var acc = 0f
            val base = f * ch
            for (c in 0 until ch) acc += pcm[base + c]
            out[f] = acc / ch
        }
        return out
    }

    /** Reads little-endian 16-bit samples from [bytes]. */
    fun shortsLe(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): ShortArray {
        val n = length / 2
        val out = ShortArray(n)
        ByteBuffer.wrap(bytes, offset, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    /** Float sample (-1..1) to 16-bit, rounding half away from zero (symmetric) and clipping. */
    fun toPcm16(x: Float): Short {
        if (x.isNaN()) return 0
        val scaled = x.toDouble().coerceIn(-1.0, 1.0) * 32767.0
        val v = if (scaled >= 0) Math.floor(scaled + 0.5) else -Math.floor(-scaled + 0.5)
        return v.toInt().toShort()
    }

    /**
     * [samples] trimmed or zero-padded to exactly [count] samples, so pieces decoded from
     * consecutive files line up with the recording timeline (decoders add or drop a few ms).
     */
    fun fitTo(samples: FloatArray, count: Int): FloatArray = when {
        count <= 0 -> FloatArray(0)
        samples.size == count -> samples
        else -> samples.copyOf(count)
    }

    /** Samples at [rate] for [ms] milliseconds. */
    fun samplesFor(ms: Long, rate: Int): Int = ((ms.coerceAtLeast(0) * rate + 500) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Milliseconds of [samples] at [rate]. */
    fun msOf(samples: Int, rate: Int): Long = if (rate <= 0) 0 else samples.toLong() * 1000 / rate

    /**
     * The frames [from, to) of a decoded buffer of [frames] frames starting at [ptsUs] that
     * fall inside [startUs, endUs) ([endUs] Long.MAX_VALUE: no end).
     */
    fun keepRange(ptsUs: Long, frames: Int, rate: Int, startUs: Long, endUs: Long): Pair<Int, Int> {
        if (frames <= 0 || rate <= 0) return 0 to 0
        val from = if (ptsUs >= startUs) 0 else ((startUs - ptsUs) * rate / 1_000_000).coerceAtMost(frames.toLong()).toInt()
        val to = if (endUs == Long.MAX_VALUE) frames else ((endUs - ptsUs) * rate / 1_000_000).coerceIn(0, frames.toLong()).toInt()
        return from to maxOf(from, to)
    }

    /** True when a buffer of [frames] at [ptsUs] reaches [endUs]. */
    fun reaches(ptsUs: Long, frames: Int, rate: Int, endUs: Long): Boolean =
        endUs != Long.MAX_VALUE && rate > 0 && ptsUs + frames * 1_000_000L / rate >= endUs

    /** Concatenates [parts] into one array. */
    fun concat(parts: List<FloatArray>): FloatArray {
        val total = parts.sumOf { it.size }
        val out = FloatArray(total)
        var at = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, at, p.size)
            at += p.size
        }
        return out
    }
}

/** Growable float buffer for decoded audio. Not thread-safe. Pure. */
class FloatBuffer(initial: Int = 16_384) {
    private var data = FloatArray(initial.coerceAtLeast(16))
    var size: Int = 0
        private set

    fun add(src: FloatArray, offset: Int = 0, length: Int = src.size - offset) {
        if (length <= 0) return
        ensure(size + length)
        System.arraycopy(src, offset, data, size, length)
        size += length
    }

    fun addZeros(count: Int) {
        if (count <= 0) return
        ensure(size + count)
        java.util.Arrays.fill(data, size, size + count, 0f)
        size += count
    }

    fun toArray(): FloatArray = data.copyOf(size)

    /**
     * The samples without a copy when the buffer is exactly full (sized up front), else a copy.
     * The buffer must not be used afterwards.
     */
    fun finish(): FloatArray = if (size == data.size) data else data.copyOf(size)

    private fun ensure(capacity: Int) {
        if (capacity <= data.size) return
        var n = data.size
        while (n < capacity) n = if (n > Int.MAX_VALUE / 2) Int.MAX_VALUE else n * 2
        data = data.copyOf(n)
    }
}
