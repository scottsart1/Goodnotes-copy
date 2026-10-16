package com.inkwell.notes.speakers

import com.inkwell.notes.speakers.logic.Pcm
import com.inkwell.notes.speakers.logic.Resampler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {
    private fun tone(freq: Double, rate: Int, seconds: Double, amp: Double = 0.5): FloatArray =
        FloatArray((rate * seconds).toInt()) { (amp * sin(2 * PI * freq * it / rate)).toFloat() }

    /** RMS of the middle of [x] (away from the zero-padded edges). */
    private fun rmsMiddle(x: FloatArray): Double {
        val a = x.size / 4
        val b = x.size * 3 / 4
        var acc = 0.0
        for (i in a until b) acc += x[i].toDouble() * x[i]
        return sqrt(acc / (b - a))
    }

    /** Amplitude of [freq] in [x] (single-bin DFT over the middle half). */
    private fun amplitudeAt(x: FloatArray, rate: Int, freq: Double): Double {
        val a = x.size / 4
        val b = x.size * 3 / 4
        var re = 0.0
        var im = 0.0
        for (i in a until b) {
            re += x[i] * kotlin.math.cos(2 * PI * freq * i / rate)
            im += x[i] * sin(2 * PI * freq * i / rate)
        }
        return 2 * sqrt(re * re + im * im) / (b - a)
    }

    @Test
    fun downsamples44100KeepsPassbandTone() {
        val out = Resampler.convert(tone(1_000.0, 44_100, 1.0), 44_100, 16_000)
        assertEquals(16_000, out.size)
        assertEquals(0.5, amplitudeAt(out, 16_000, 1_000.0), 0.01)
    }

    @Test
    fun downsamples48000KeepsPassbandTone() {
        val out = Resampler.convert(tone(3_000.0, 48_000, 1.0), 48_000, 16_000)
        assertEquals(16_000, out.size)
        assertEquals(0.5, amplitudeAt(out, 16_000, 3_000.0), 0.01)
    }

    @Test
    fun tonesAboveTargetNyquistDoNotAlias() {
        // 11 kHz at 44.1 kHz would fold to 5 kHz at 16 kHz without the anti-aliasing filter.
        val out44 = Resampler.convert(tone(11_000.0, 44_100, 1.0), 44_100, 16_000)
        assertTrue("alias at 5 kHz: ${amplitudeAt(out44, 16_000, 5_000.0)}", amplitudeAt(out44, 16_000, 5_000.0) < 0.005)
        assertTrue(rmsMiddle(out44) < 0.005)
        // 13 kHz at 48 kHz would fold to 3 kHz.
        val out48 = Resampler.convert(tone(13_000.0, 48_000, 1.0), 48_000, 16_000)
        assertTrue(rmsMiddle(out48) < 0.005)
    }

    @Test
    fun upsamplesAndHandlesUnusualRates() {
        for (rate in listOf(8_000, 11_025, 22_050, 32_000, 37_800, 44_056, 96_000)) {
            val out = Resampler.convert(tone(440.0, rate, 1.0), rate, 16_000)
            assertEquals("length at $rate", 16_000.0, out.size.toDouble(), 1.0)
            assertEquals("amplitude at $rate", 0.5, amplitudeAt(out, 16_000, 440.0), 0.01)
        }
    }

    @Test
    fun streamingInChunksEqualsWholeSignal() {
        val input = tone(700.0, 44_100, 0.5).also { x -> for (i in x.indices) x[i] += (0.1 * sin(i * 0.37)).toFloat() }
        val whole = Resampler.convert(input, 44_100, 16_000)
        val r = Resampler(44_100, 16_000)
        val parts = ArrayList<FloatArray>()
        var at = 0
        val sizes = intArrayOf(1, 7, 333, 4096, 50, 10_000)
        var k = 0
        while (at < input.size) {
            val n = minOf(sizes[k++ % sizes.size], input.size - at)
            parts += r.process(input, at, n)
            at += n
        }
        parts += r.flush()
        val streamed = Pcm.concat(parts)
        assertEquals(whole.size, streamed.size)
        for (i in whole.indices) assertEquals("sample $i", whole[i], streamed[i], 1e-5f)
    }

    @Test
    fun passThroughAtSameRate() {
        val input = floatArrayOf(0.1f, -0.2f, 0.3f)
        assertArrayEquals(input, Resampler.convert(input, 16_000, 16_000), 0f)
    }

    @Test
    fun dcLevelIsPreservedAndAligned() {
        val input = FloatArray(44_100) { 0.25f }
        val out = Resampler.convert(input, 44_100, 16_000)
        for (i in 200 until out.size - 200) assertEquals(0.25f, out[i], 1e-4f)
        // An impulse lands where it should: no group delay.
        val impulse = FloatArray(48_000).also { it[24_000] = 1f }
        val o = Resampler.convert(impulse, 48_000, 16_000)
        val peak = o.indices.maxBy { abs(o[it]) }
        assertEquals(8_000, peak)
    }

    @Test
    fun outputLengthMatchesConversion() {
        val r = Resampler(44_100, 16_000)
        assertEquals(16_000L, r.outputLength(44_100))
        assertEquals(Resampler.convert(FloatArray(12_345), 44_100, 16_000).size.toLong(), r.outputLength(12_345))
    }
}
