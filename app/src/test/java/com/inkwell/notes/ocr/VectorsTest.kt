package com.inkwell.notes.ocr

import com.inkwell.notes.ocr.text.Vectors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VectorsTest {
    @Test
    fun encodesLittleEndianFloat32() {
        val bytes = Vectors.encode(floatArrayOf(1f, -2f))
        // 1.0f = 0x3F800000, -2.0f = 0xC0000000, least significant byte first.
        assertArrayEquals(byteArrayOf(0, 0, 0x80.toByte(), 0x3F, 0, 0, 0, 0xC0.toByte()), bytes)
    }

    @Test
    fun roundTrips() {
        val v = floatArrayOf(0.1f, -0.25f, 3.5f, 0f, 1e-7f)
        assertArrayEquals(v, Vectors.decode(Vectors.encode(v))!!, 0f)
    }

    @Test
    fun decodeRejectsBrokenData() {
        assertNull(Vectors.decode(ByteArray(0)))
        assertNull(Vectors.decode(ByteArray(7)))
        assertNull(Vectors.decode(Vectors.encode(floatArrayOf(1f, Float.NaN))))
    }

    @Test
    fun cosineSimilarity() {
        val a = floatArrayOf(1f, 2f, 3f)
        assertEquals(1f, Vectors.cosine(a, floatArrayOf(2f, 4f, 6f)), 1e-6f)
        assertEquals(-1f, Vectors.cosine(a, floatArrayOf(-1f, -2f, -3f)), 1e-6f)
        assertEquals(0f, Vectors.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 5f)), 1e-6f)
        assertEquals(0f, Vectors.cosine(a, floatArrayOf(1f, 2f)), 0f)
        assertEquals(0f, Vectors.cosine(a, floatArrayOf(0f, 0f, 0f)), 0f)
        assertEquals(0f, Vectors.cosine(FloatArray(0), FloatArray(0)), 0f)
        assertEquals(0.7071f, Vectors.cosine(floatArrayOf(1f, 0f), floatArrayOf(1f, 1f)), 1e-4f)
    }

    @Test
    fun cosineOnEncodedVectorsMatches() {
        val q = floatArrayOf(0.3f, -0.4f, 0.5f, 0.1f)
        val other = floatArrayOf(0.2f, 0.1f, 0.9f, -0.3f)
        val expected = Vectors.cosine(q, other)
        assertEquals(expected, Vectors.cosineEncoded(q, Vectors.norm(q), Vectors.encode(other)), 1e-6f)
        assertEquals(0f, Vectors.cosineEncoded(q, Vectors.norm(q), Vectors.encode(floatArrayOf(1f))), 0f)
        assertEquals(0f, Vectors.cosineEncoded(q, 0.0, Vectors.encode(other)), 0f)
    }
}
