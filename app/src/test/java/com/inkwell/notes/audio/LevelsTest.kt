package com.inkwell.notes.audio

import com.inkwell.notes.audio.logic.LevelMeter
import com.inkwell.notes.audio.logic.Waveform
import com.inkwell.notes.audio.logic.WaveformAccumulator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LevelsTest {
    @Test
    fun silenceAndFullScale() {
        assertEquals(0f, LevelMeter.normalize(0), 0f)
        assertEquals(0f, LevelMeter.normalize(-3), 0f)
        assertEquals(1f, LevelMeter.normalize(32767), 1e-6f)
        assertEquals(1f, LevelMeter.normalize(100_000), 1e-6f)
    }

    @Test
    fun levelIsLogarithmic() {
        // -25 dBFS is half way between the -50 dB floor and full scale.
        val amp = (32767 * Math.pow(10.0, -25.0 / 20)).toInt()
        assertEquals(0.5f, LevelMeter.normalize(amp), 0.01f)
        // Below the floor reads as silence.
        val quiet = (32767 * Math.pow(10.0, -60.0 / 20)).toInt()
        assertEquals(0f, LevelMeter.normalize(quiet), 0f)
    }

    @Test
    fun levelIsMonotonic() {
        var last = -1f
        for (a in listOf(1, 10, 100, 500, 1_000, 5_000, 10_000, 20_000, 32_767)) {
            val l = LevelMeter.normalize(a)
            assertTrue("$a -> $l", l >= last)
            assertTrue(l in 0f..1f)
            last = l
        }
    }

    @Test
    fun smoothingRisesFastAndFallsSlowly() {
        val up = LevelMeter.smooth(0f, 1f)
        val down = LevelMeter.smooth(1f, 0f)
        assertTrue(up > 1f - down)
        assertTrue(up in 0f..1f && down in 0f..1f)
        assertEquals(0.5f, LevelMeter.smooth(0.5f, 0.5f), 0f)
    }

    @Test
    fun quantizationRoundTrips() {
        assertEquals(0.toByte(), Waveform.quantize(0f))
        assertEquals(255.toByte(), Waveform.quantize(1f))
        assertEquals(255.toByte(), Waveform.quantize(7f))
        assertEquals(0.toByte(), Waveform.quantize(-1f))
        assertEquals(128.toByte(), Waveform.quantize(0.5f))
        for (i in 0..255) {
            val b = i.toByte()
            assertEquals(b, Waveform.quantize(Waveform.dequantize(b)))
        }
        for (v in listOf(0f, 0.1f, 0.33f, 0.5f, 0.77f, 1f)) {
            assertTrue(abs(Waveform.dequantize(Waveform.quantize(v)) - v) <= 0.5f / 255f + 1e-6f)
        }
    }

    @Test
    fun accumulatorKeepsTenBucketsPerSecondWithPeaks() {
        val acc = WaveformAccumulator()
        // Polls every 50 ms for one second.
        for (i in 0 until 20) acc.add(if (i % 2 == 0) 0.2f else 0.8f, i * 50L)
        acc.add(0.1f, 1_000) // opens bucket 10, closing 0..9
        assertEquals(10, acc.length)
        val snap = acc.snapshot()
        assertEquals(11, snap.size)
        for (i in 0 until 10) assertEquals(Waveform.quantize(0.8f), snap[i])
        assertEquals(Waveform.quantize(0.1f), snap[10])
    }

    @Test
    fun accumulatorFollowsTimeNotSampleCount() {
        val acc = WaveformAccumulator()
        acc.add(0.5f, 0)
        // A stalled poll: the next sample arrives 450 ms later.
        acc.add(0.9f, 450)
        val snap = acc.snapshot()
        assertEquals(5, snap.size)
        // Skipped buckets are filled with the last peak.
        for (i in 0 until 4) assertEquals(Waveform.quantize(0.5f), snap[i])
        assertEquals(Waveform.quantize(0.9f), snap[4])
        // Late samples for written buckets are ignored.
        acc.add(1f, 100)
        assertEquals(4, acc.length)
    }

    @Test
    fun trimAndDecode() {
        val bytes = ByteArray(50) { Waveform.quantize(it / 49f) }
        assertEquals(30, Waveform.trim(bytes, 2_950).size)
        assertEquals(50, Waveform.trim(bytes, 60_000).size)
        assertEquals(0, Waveform.trim(bytes, 0).size)
        val decoded = Waveform.decode(byteArrayOf(0, 255.toByte()))
        assertArrayEquals(floatArrayOf(0f, 1f), decoded, 0f)
        assertEquals(3, Waveform.bucketsFor(201))
    }

    @Test
    fun downsampleKeepsPeaks() {
        val v = FloatArray(100) { if (it == 37) 1f else 0.1f }
        val d = Waveform.downsample(v, 10)
        assertEquals(10, d.size)
        assertEquals(1f, d[3], 0f)
        assertEquals(0.1f, d[0], 0f)
        // Fewer values than bars: stretched, never empty.
        val s = Waveform.downsample(floatArrayOf(0.2f, 0.4f), 4)
        assertArrayEquals(floatArrayOf(0.2f, 0.2f, 0.4f, 0.4f), s, 0f)
        assertEquals(0, Waveform.downsample(v, 0).size)
        assertArrayEquals(FloatArray(3), Waveform.downsample(FloatArray(0), 3), 0f)
    }
}
