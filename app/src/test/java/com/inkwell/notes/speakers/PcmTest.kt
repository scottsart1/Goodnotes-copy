package com.inkwell.notes.speakers

import com.inkwell.notes.speakers.logic.FloatBuffer
import com.inkwell.notes.speakers.logic.Pcm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmTest {
    @Test
    fun downmixAveragesChannels() {
        val stereo = shortArrayOf(16_384, -16_384, 32_767, 32_767, 0, 8_192)
        assertArrayEquals(floatArrayOf(0f, 32_767f / 32_768f, 0.125f), Pcm.downmix16(stereo, stereo.size, 2), 1e-6f)
        assertArrayEquals(floatArrayOf(0.5f), Pcm.downmix16(shortArrayOf(16_384), 1, 1), 1e-6f)
        assertArrayEquals(floatArrayOf(0.25f, 0.5f), Pcm.downmixFloat(floatArrayOf(0f, 0.5f, 1f, 0f), 4, 2), 1e-6f)
        // A trailing partial frame is ignored.
        assertEquals(1, Pcm.downmix16(shortArrayOf(1, 2, 3), 3, 2).size)
    }

    @Test
    fun keepRangeTrimsBuffersToTheRequestedTime() {
        // 1024 frames at 44.1 kHz starting at 1 s; keep from 1.01 s to 1.02 s.
        val (from, to) = Pcm.keepRange(1_000_000, 1_024, 44_100, 1_010_000, 1_020_000)
        assertEquals(441, from)
        assertEquals(882, to)
        assertEquals(0 to 1_024, Pcm.keepRange(2_000_000, 1_024, 44_100, 0, Long.MAX_VALUE))
        // Entirely before the start, entirely after the end.
        assertEquals(1_024 to 1_024, Pcm.keepRange(0, 1_024, 44_100, 5_000_000, Long.MAX_VALUE))
        assertEquals(0 to 0, Pcm.keepRange(5_000_000, 1_024, 44_100, 0, 1_000_000))
        assertTrue(Pcm.reaches(1_000_000, 1_024, 44_100, 1_020_000))
        assertFalse(Pcm.reaches(1_000_000, 100, 44_100, 1_020_000))
        assertFalse(Pcm.reaches(1_000_000, 100_000, 44_100, Long.MAX_VALUE))
    }

    @Test
    fun fitAndConversions() {
        assertArrayEquals(floatArrayOf(1f, 2f, 0f), Pcm.fitTo(floatArrayOf(1f, 2f), 3), 0f)
        assertArrayEquals(floatArrayOf(1f), Pcm.fitTo(floatArrayOf(1f, 2f), 1), 0f)
        assertEquals(0, Pcm.fitTo(floatArrayOf(1f), -4).size)
        assertEquals(16_000, Pcm.samplesFor(1_000, 16_000))
        assertEquals(0, Pcm.samplesFor(-5, 16_000))
        assertEquals(1_000L, Pcm.msOf(16_000, 16_000))
        assertEquals(0.toShort(), Pcm.toPcm16(Float.NaN))
        assertEquals((-32_767).toShort(), Pcm.toPcm16(-3f))
    }

    @Test
    fun floatBufferGrowsAndHandsOverExactBuffers() {
        val b = FloatBuffer(16)
        b.add(FloatArray(20) { it.toFloat() })
        b.addZeros(5)
        b.add(floatArrayOf(9f, 8f, 7f), 1, 2)
        val arr = b.toArray()
        assertEquals(27, arr.size)
        assertEquals(19f, arr[19], 0f)
        assertEquals(0f, arr[24], 0f)
        assertEquals(7f, arr[26], 0f)
        val exact = FloatBuffer(16)
        exact.add(FloatArray(16) { 1f })
        val a = exact.finish()
        assertEquals(16, a.size)
        assertEquals(1f, a[15], 0f)
    }
}
