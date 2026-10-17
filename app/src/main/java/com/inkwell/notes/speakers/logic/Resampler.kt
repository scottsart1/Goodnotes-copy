package com.inkwell.notes.speakers.logic

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming band-limited sample-rate converter (windowed-sinc, Kaiser window, polyphase).
 *
 * The ratio is reduced to L/M (44.1 kHz -> 16 kHz is 160/441, 48 kHz -> 16 kHz is 1/3). Every
 * output sample n sits at input position n*M/L; its taps are a low-pass sinc centred there,
 * cut off below the lower of the two Nyquist frequencies so downsampling does not alias.
 * Phases are tabulated exactly when L <= [MAX_PHASES]; for unusual rates the table is
 * interpolated linearly between neighbouring phases. Feed input in chunks of any size with
 * [process] and call [flush] once at the end; the output equals converting the whole signal
 * at once, aligned with zero delay. Mono float samples. Not thread-safe. Pure.
 */
class Resampler(val inRate: Int, val outRate: Int) {
    init {
        require(inRate > 0 && outRate > 0) { "Sample rates must be positive" }
    }

    private val g = gcd(inRate, outRate)
    private val up = (outRate / g).toLong()
    private val down = (inRate / g).toLong()
    private val passThrough = up == down

    /** Cutoff in cycles per input sample. */
    private val cutoff = 0.5 * min(1.0, up.toDouble() / down) * ROLLOFF
    private val halfWidth = if (passThrough) 0 else ceil(ZERO_CROSSINGS / (2.0 * cutoff)).toInt()
    private val taps = 2 * halfWidth
    private val phases: Int = if (up <= MAX_PHASES) up.toInt() else MAX_PHASES
    private val exactPhases = up <= MAX_PHASES

    /** [phases] + 1 rows (the extra row is phase 1.0, used when interpolating). */
    private val table: Array<FloatArray> = if (passThrough) emptyArray() else Array(phases + 1) { p -> row(p.toDouble() / phases) }

    // Input history: buf[0] is absolute input index bufStart. Starts with halfWidth-1 zeros
    // standing for the samples before the signal.
    private var buf = FloatArray(maxOf(1024, taps * 4))
    private var bufLen = if (passThrough) 0 else halfWidth - 1
    private var bufStart = if (passThrough) 0L else -(halfWidth - 1).toLong()
    private var totalIn = 0L
    private var nextOut = 0L
    private var flushed = false

    /** Converts the next [length] input samples; returns the output they complete. */
    fun process(input: FloatArray, offset: Int = 0, length: Int = input.size - offset): FloatArray {
        check(!flushed) { "Resampler already flushed" }
        require(offset >= 0 && length >= 0 && offset + length <= input.size)
        if (length == 0) return EMPTY
        if (passThrough) {
            totalIn += length
            return input.copyOfRange(offset, offset + length)
        }
        append(input, offset, length)
        totalIn += length
        return drain(limitToInput = false)
    }

    /** Ends the stream: the remaining output, as if the signal were followed by silence. */
    fun flush(): FloatArray {
        if (flushed) return EMPTY
        flushed = true
        if (passThrough) return EMPTY
        append(FloatArray(halfWidth + 1), 0, halfWidth + 1)
        return drain(limitToInput = true)
    }

    /** Number of output samples a whole signal of [inputSamples] converts to. */
    fun outputLength(inputSamples: Long): Long = if (passThrough) inputSamples else (inputSamples * up + down - 1) / down

    private fun append(src: FloatArray, off: Int, len: Int) {
        if (bufLen + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + len))
        System.arraycopy(src, off, buf, bufLen, len)
        bufLen += len
    }

    private fun drain(limitToInput: Boolean): FloatArray {
        val bufEnd = bufStart + bufLen
        // Output n needs input up to floor(n*M/L) + halfWidth.
        var count = 0L
        run {
            var n = nextOut
            while (true) {
                val ip = Math.floorDiv(n * down, up)
                if (ip + halfWidth >= bufEnd) break
                if (limitToInput && n * down >= totalIn * up) break
                n++
            }
            count = n - nextOut
        }
        if (count <= 0) return EMPTY
        val out = FloatArray(count.toInt())
        for (k in out.indices) {
            val n = nextOut + k
            val num = n * down
            val ip = Math.floorDiv(num, up)
            val frac = num - ip * up
            val first = (ip - halfWidth + 1 - bufStart).toInt()
            out[k] = if (exactPhases) {
                dot(table[frac.toInt()], first)
            } else {
                val pos = frac.toDouble() * phases / up
                val p0 = pos.toInt()
                val t = (pos - p0).toFloat()
                val a = dot(table[p0], first)
                val b = dot(table[p0 + 1], first)
                a + (b - a) * t
            }
        }
        nextOut += count
        // Drop input no later output needs.
        val keepFrom = Math.floorDiv(nextOut * down, up) - halfWidth + 1
        val drop = (keepFrom - bufStart).toInt().coerceIn(0, bufLen)
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufLen -= drop
            bufStart += drop
        }
        return out
    }

    private fun dot(h: FloatArray, first: Int): Float {
        var acc = 0f
        for (j in 0 until taps) acc += h[j] * buf[first + j]
        return acc
    }

    /** Taps for an output at fractional input position f (0..1) past sample index ip. */
    private fun row(f: Double): FloatArray {
        val h = FloatArray(taps)
        var sum = 0.0
        for (j in 0 until taps) {
            // Input sample k = ip - halfWidth + 1 + j sits at distance f + halfWidth - 1 - j.
            val t = f + halfWidth - 1 - j
            val v = 2 * cutoff * sinc(2 * cutoff * t) * kaiser(t / (halfWidth.toDouble()))
            h[j] = v.toFloat()
            sum += v
        }
        // Unity gain at DC for every phase.
        if (abs(sum) > 1e-12) for (j in 0 until taps) h[j] = (h[j] / sum).toFloat()
        return h
    }

    companion object {
        /** Exact polyphase tables up to this many phases; interpolated beyond. */
        const val MAX_PHASES = 1024

        /** Passband edge as a fraction of the target Nyquist (the rest is the transition band). */
        private const val ROLLOFF = 0.92

        /** Sinc zero crossings on each side of the centre. */
        private const val ZERO_CROSSINGS = 16.0

        /** Kaiser window beta (~80 dB stopband). */
        private const val BETA = 8.0

        private val EMPTY = FloatArray(0)
        private val I0_BETA = besselI0(BETA)

        /** Resamples a whole signal at once. */
        fun convert(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
            val r = Resampler(inRate, outRate)
            val a = r.process(input)
            val b = r.flush()
            return if (b.isEmpty()) a else a + b
        }

        private fun sinc(x: Double): Double = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)

        private fun kaiser(x: Double): Double {
            if (abs(x) >= 1.0) return 0.0
            return besselI0(BETA * sqrt(1 - x * x)) / I0_BETA
        }

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val q = x * x / 4
            var k = 1
            while (k < 200) {
                term *= q / (k.toDouble() * k)
                sum += term
                if (term < sum * 1e-12) break
                k++
            }
            return sum
        }

        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}
