package com.inkwell.notes.speakers

import com.inkwell.notes.speakers.logic.Pcm
import com.inkwell.notes.speakers.logic.ReferenceClip
import com.inkwell.notes.speakers.logic.ReferenceClipCodec
import com.inkwell.notes.speakers.logic.ReferencePolicy
import com.inkwell.notes.speakers.logic.VoiceVector
import com.inkwell.notes.speakers.logic.Voiceprint
import com.inkwell.notes.speakers.logic.VoiceprintCodec
import com.inkwell.notes.speakers.logic.WavFormat
import com.inkwell.notes.speakers.logic.WavWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavAndCodecTest {
    @Test
    fun wavWriterProducesStandardHeader() {
        val wav = WavWriter.encode(floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f, 2f), 16_000)
        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(36 + 12, bb.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals(16, bb.getInt(16))
        assertEquals(1, bb.getShort(20).toInt())
        assertEquals(1, bb.getShort(22).toInt())
        assertEquals(16_000, bb.getInt(24))
        assertEquals(32_000, bb.getInt(28))
        assertEquals(2, bb.getShort(32).toInt())
        assertEquals(16, bb.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4))
        assertEquals(12, bb.getInt(40))
        assertEquals(44 + 12, wav.size)
        val samples = Pcm.shortsLe(wav, 44)
        assertArrayEquals(shortArrayOf(0, 16_384, -16_384, 32_767, -32_767, 32_767), samples)
        assertEquals(0L, ReferencePolicy.wavDurationMs(44))
        assertEquals(1_000L, ReferencePolicy.wavDurationMs(WavWriter.encode(FloatArray(16_000)).size))
    }

    @Test
    fun wavRoundTripsThroughParser() {
        val input = FloatArray(1_000) { ((it % 50) - 25) / 50f }
        val wav = WavWriter.encode(input, 16_000)
        val fmt = WavFormat.parse(wav)!!
        assertEquals(16_000, fmt.sampleRate)
        assertEquals(1, fmt.channels)
        assertEquals(16, fmt.bitsPerSample)
        assertEquals(44L, fmt.dataOffset)
        assertEquals(2_000L, fmt.dataBytes)
        val back = fmt.toMono(wav.copyOfRange(44, wav.size), 2_000)
        for (i in input.indices) assertEquals(input[i], back[i], 1f / 16_000)
    }

    private fun wavHeader(tag: Int, channels: Int, rate: Int, bits: Int, extensible: Boolean = false, extraChunk: Boolean = false, dataSize: Int = 0): ByteArray {
        val bb = ByteBuffer.allocate(200).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(0).put("WAVE".toByteArray())
        if (extraChunk) bb.put("LIST".toByteArray()).putInt(3).put(byteArrayOf(1, 2, 3, 0)) // odd size, padded
        bb.put("fmt ".toByteArray()).putInt(if (extensible) 40 else 16)
        bb.putShort((if (extensible) 0xFFFE else tag).toShort()).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        if (extensible) {
            bb.putShort(22).putShort(bits.toShort()).putInt(3)
            bb.putShort(tag.toShort()).put(ByteArray(14))
        }
        bb.put("data".toByteArray()).putInt(dataSize)
        return bb.array().copyOf(bb.position())
    }

    @Test
    fun parsesStereoFloatExtensibleAndSkipsChunks() {
        val f = WavFormat.parse(wavHeader(3, 2, 48_000, 32, extensible = true, extraChunk = true, dataSize = 16))!!
        assertTrue(f.isFloat)
        assertEquals(2, f.channels)
        assertEquals(48_000, f.sampleRate)
        assertEquals(16L, f.dataBytes)
        val data = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.5f).putFloat(-0.5f).putFloat(1f).putFloat(0f).array()
        assertArrayEquals(floatArrayOf(0f, 0.5f), f.toMono(data, 16), 1e-6f)
    }

    @Test
    fun parses24BitAndStreamedSize() {
        val f = WavFormat.parse(wavHeader(1, 1, 44_100, 24, dataSize = 0))!!
        assertEquals(-1L, f.dataBytes)
        // -0.5 and +0.25 in 24-bit little-endian.
        val data = byteArrayOf(0x00, 0x00, 0xC0.toByte(), 0x00, 0x00, 0x20)
        assertArrayEquals(floatArrayOf(-0.5f, 0.25f), f.toMono(data, 6), 1e-6f)
    }

    @Test
    fun rejectsUnsupportedOrBrokenWav() {
        assertNull(WavFormat.parse("not a wav file at all".toByteArray()))
        assertNull(WavFormat.parse(wavHeader(2, 1, 16_000, 16))) // ADPCM
        assertNull(WavFormat.parse(wavHeader(1, 1, 16_000, 12)))
        assertNull(WavFormat.parse(wavHeader(1, 1, 16_000, 16).copyOf(30)))
        assertFalse(WavFormat.looksLikeWav(ByteArray(4)))
    }

    private fun print(n: Int = 2, dim: Int = 4) = Voiceprint(
        modelId = "wespeaker_resnet34_lm",
        dim = dim,
        vectors = List(n) { i -> VoiceVector("rec:r$i:S${i + 1}", 1_000f * (i + 1), FloatArray(dim) { (it + i) * 0.25f - 0.5f }) },
    )

    @Test
    fun voiceprintCodecRoundTrips() {
        for (p in listOf(print(), print(0), print(30, 256), print(1).copy(modelId = "über-modèl"))) {
            val back = VoiceprintCodec.decode(VoiceprintCodec.encode(p))
            assertEquals(p, back)
        }
    }

    @Test
    fun voiceprintCodecIsLittleEndianWithMagicAndVersion() {
        val bytes = VoiceprintCodec.encode(print(1, 2))
        assertEquals("IKVP", String(bytes, 0, 4))
        assertEquals(1, bytes[4].toInt())
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val modelLen = bb.getShort(5).toInt()
        assertEquals(2, bb.getInt(7 + modelLen))
        assertEquals(1, bb.getInt(11 + modelLen))
    }

    @Test
    fun voiceprintCodecRejectsCorruption() {
        val good = VoiceprintCodec.encode(print())
        assertNull(VoiceprintCodec.decode(ByteArray(0)))
        assertNull(VoiceprintCodec.decode(good.copyOf(good.size - 1)))
        assertNull(VoiceprintCodec.decode(good + byteArrayOf(0)))
        assertNull(VoiceprintCodec.decode(good.copyOf().also { it[0] = 'X'.code.toByte() }))
        assertNull(VoiceprintCodec.decode(good.copyOf().also { it[4] = 9 }))
        // A dimension that does not fit the bytes.
        val bb = ByteBuffer.wrap(good.copyOf()).order(ByteOrder.LITTLE_ENDIAN)
        val modelLen = bb.getShort(5).toInt()
        bb.putInt(7 + modelLen, 100_000)
        assertNull(VoiceprintCodec.decode(bb.array()))
    }

    @Test
    fun referenceClipCodecRoundTrips() {
        val clip = ReferenceClip(7_500, WavWriter.encode(FloatArray(100) { it / 100f }), "rec:r1:S2")
        assertEquals(clip, ReferenceClipCodec.decode(ReferenceClipCodec.encode(clip)))
        val bytes = ReferenceClipCodec.encode(clip)
        assertNull(ReferenceClipCodec.decode(bytes.copyOf(bytes.size - 3)))
        assertNull(ReferenceClipCodec.decode(VoiceprintCodec.encode(print())))
        assertNotNull(ReferenceClipCodec.decode(ReferenceClipCodec.encode(ReferenceClip(0, ByteArray(0)))))
    }
}
