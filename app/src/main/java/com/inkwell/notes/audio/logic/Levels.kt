package com.inkwell.notes.audio.logic

import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/** Converts MediaRecorder amplitudes (0..32767) into a perceptual 0..1 level. */
object LevelMeter {
    const val MAX_AMPLITUDE = 32767

    /** Quietest level shown; anything below reads as silence. */
    const val FLOOR_DB = -50.0

    /** Logarithmic (dBFS) mapping of [amplitude] onto 0..1 between [FLOOR_DB] and full scale. */
    fun normalize(amplitude: Int): Float {
        if (amplitude <= 0) return 0f
        val db = 20.0 * log10(amplitude.coerceAtMost(MAX_AMPLITUDE) / MAX_AMPLITUDE.toDouble())
        return ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0.0, 1.0).toFloat()
    }

    /** Meter ballistics: fast rise, slower fall, so speech reads as movement rather than flicker. */
    fun smooth(previous: Float, target: Float, attack: Float = 0.65f, release: Float = 0.2f): Float {
        val k = if (target > previous) attack else release
        return (previous + (target - previous) * k).coerceIn(0f, 1f)
    }
}

/**
 * Amplitude envelope stored next to the audio as waveform.bin: one unsigned byte
 * (0..255 = level 0..1) per [BUCKET_MS] of recorded time, no header.
 */
object Waveform {
    const val BUCKET_MS = 100L

    fun quantize(level: Float): Byte = (level.coerceIn(0f, 1f) * 255f).roundToInt().toByte()

    fun dequantize(b: Byte): Float = (b.toInt() and 0xFF) / 255f

    fun decode(bytes: ByteArray): FloatArray = FloatArray(bytes.size) { dequantize(bytes[it]) }

    /** Buckets needed to cover [durationMs]. */
    fun bucketsFor(durationMs: Long): Int = ceil(durationMs.coerceAtLeast(0) / BUCKET_MS.toDouble()).toInt()

    /** Keeps only the part of the envelope covered by [durationMs] of audio. */
    fun trim(bytes: ByteArray, durationMs: Long): ByteArray {
        val n = bucketsFor(durationMs)
        return if (bytes.size <= n) bytes else bytes.copyOf(n)
    }

    /** Peak-preserving reduction of [values] to [bars] columns for drawing. */
    fun downsample(values: FloatArray, bars: Int): FloatArray {
        if (bars <= 0) return FloatArray(0)
        if (values.isEmpty()) return FloatArray(bars)
        if (values.size <= bars) {
            // Stretch: each bar shows the bucket under it.
            return FloatArray(bars) { values[(it.toLong() * values.size / bars).toInt().coerceAtMost(values.lastIndex)] }
        }
        val out = FloatArray(bars)
        for (b in 0 until bars) {
            val from = (b.toLong() * values.size / bars).toInt()
            val to = ((b + 1).toLong() * values.size / bars).toInt().coerceAtLeast(from + 1).coerceAtMost(values.size)
            var peak = 0f
            for (i in from until to) peak = max(peak, values[i])
            out[b] = peak
        }
        return out
    }
}

/**
 * Builds the envelope while recording. Samples arrive every ~50ms with the recorded time
 * they were taken at; each [Waveform.BUCKET_MS] bucket keeps its peak. Buckets are placed by
 * time rather than by sample count, so polling jitter or a stalled poll never shifts the
 * envelope against the audio. Not thread-safe.
 */
class WaveformAccumulator(initial: ByteArray = ByteArray(0)) {
    private var data = initial.copyOf(max(initial.size, 256))
    private var size = initial.size
    private var bucketPeak = 0f
    private var bucketHasSample = false

    /** Completed buckets. */
    val length: Int get() = size

    fun add(level: Float, elapsedMs: Long) {
        val bucket = (elapsedMs.coerceAtLeast(0) / Waveform.BUCKET_MS).toInt()
        if (bucket < size) {
            // Late sample for a bucket already written (e.g. after a correction): ignore.
            return
        }
        if (bucket > size) {
            // Close the open bucket, and fill any skipped ones with its peak.
            val fill = if (bucketHasSample) bucketPeak else level
            while (size < bucket) append(Waveform.quantize(fill))
            bucketPeak = 0f
            bucketHasSample = false
        }
        bucketPeak = max(bucketPeak, level.coerceIn(0f, 1f))
        bucketHasSample = true
    }

    /** Completed buckets plus the open one (if it has samples). */
    fun snapshot(): ByteArray {
        val out = data.copyOf(size + if (bucketHasSample) 1 else 0)
        if (bucketHasSample) out[size] = Waveform.quantize(bucketPeak)
        return out
    }

    private fun append(b: Byte) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = b
    }
}
