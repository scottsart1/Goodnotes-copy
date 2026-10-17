package com.inkwell.notes.speakers.logic

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes 16-bit PCM WAV files (RIFF, little-endian). Pure. */
object WavWriter {
    const val HEADER_BYTES = 44

    /** A mono 16-bit WAV of [samples] (-1..1) at [sampleRate]. */
    fun encode(samples: FloatArray, sampleRate: Int = 16_000): ByteArray {
        require(sampleRate > 0)
        val dataBytes = samples.size.toLong() * 2
        require(dataBytes + HEADER_BYTES - 8 <= 0xFFFF_FFFFL) { "Clip too long for WAV" }
        val buf = ByteBuffer.allocate(HEADER_BYTES + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt((36 + dataBytes).toInt())
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(1) // mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2) // byte rate
        buf.putShort(2) // block align
        buf.putShort(16) // bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataBytes.toInt())
        for (s in samples) buf.putShort(Pcm.toPcm16(s))
        return buf.array()
    }
}

/**
 * The format and data location of a WAV file, parsed from its first bytes. Supports PCM
 * 8/16/24/32-bit, IEEE float 32-bit and WAVE_FORMAT_EXTENSIBLE wrapping either. Pure.
 */
data class WavFormat(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val isFloat: Boolean,
    /** Offset of the first sample byte. */
    val dataOffset: Long,
    /** Bytes of sample data; -1 when the header does not say (streamed files: read to the end). */
    val dataBytes: Long,
) {
    val blockAlign: Int get() = channels * (bitsPerSample / 8)

    /** Byte offset (from the start of the file) of frame [frame]. */
    fun offsetOfFrame(frame: Long): Long = dataOffset + frame * blockAlign

    /** Converts whole frames in [bytes] to mono floats (-1..1). */
    fun toMono(bytes: ByteArray, length: Int): FloatArray {
        val align = blockAlign
        if (align <= 0) return FloatArray(0)
        val frames = length / align
        val out = FloatArray(frames)
        val bb = ByteBuffer.wrap(bytes, 0, frames * align).order(ByteOrder.LITTLE_ENDIAN)
        val width = bitsPerSample / 8
        for (f in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) {
                val at = f * align + c * width
                acc += when {
                    isFloat && width == 4 -> bb.getFloat(at).let { if (it.isFinite()) it else 0f }
                    isFloat && width == 8 -> bb.getDouble(at).toFloat().let { if (it.isFinite()) it else 0f }
                    width == 1 -> ((bytes[at].toInt() and 0xFF) - 128) / 128f
                    width == 2 -> bb.getShort(at) / 32768f
                    width == 3 -> {
                        val v = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or (bytes[at + 2].toInt() shl 16)
                        v / 8_388_608f
                    }
                    width == 4 -> (bb.getInt(at) / 2_147_483_648.0).toFloat()
                    else -> 0f
                }
            }
            out[f] = (acc / channels).coerceIn(-1f, 1f)
        }
        return out
    }

    companion object {
        private const val FORMAT_PCM = 1
        private const val FORMAT_FLOAT = 3
        private const val FORMAT_EXTENSIBLE = 0xFFFE

        /** True when [head] starts like a RIFF/WAVE file. */
        fun looksLikeWav(head: ByteArray): Boolean =
            head.size >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WAVE"

        /**
         * Parses the header from the first bytes of a file ([head], e.g. 64 KiB). Null when it
         * is not a WAV this parser can read, or the data chunk does not start within [head].
         */
        fun parse(head: ByteArray): WavFormat? {
            if (!looksLikeWav(head)) return null
            val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            var at = 12
            var rate = 0
            var channels = 0
            var bits = 0
            var float = false
            var haveFmt = false
            while (at + 8 <= head.size) {
                val id = String(head, at, 4, Charsets.US_ASCII)
                val size = bb.getInt(at + 4).toLong() and 0xFFFF_FFFFL
                val body = at + 8
                when (id) {
                    "fmt " -> {
                        if (size < 16 || body + 16 > head.size) return null
                        var tag = bb.getShort(body).toInt() and 0xFFFF
                        channels = bb.getShort(body + 2).toInt() and 0xFFFF
                        rate = bb.getInt(body + 4)
                        bits = bb.getShort(body + 14).toInt() and 0xFFFF
                        if (tag == FORMAT_EXTENSIBLE) {
                            if (size < 40 || body + 26 > head.size) return null
                            // The first two bytes of the sub-format GUID are the real format tag.
                            tag = bb.getShort(body + 24).toInt() and 0xFFFF
                        }
                        float = when (tag) {
                            FORMAT_PCM -> false
                            FORMAT_FLOAT -> true
                            else -> return null
                        }
                        haveFmt = true
                    }
                    "data" -> {
                        if (!haveFmt) return null
                        if (rate <= 0 || channels <= 0 || channels > 32) return null
                        val okBits = if (float) bits == 32 || bits == 64 else bits in setOf(8, 16, 24, 32)
                        if (!okBits) return null
                        // 0 and 0xFFFFFFFF mark a streamed file whose size was never written.
                        val bytes = if (size == 0L || size == 0xFFFF_FFFFL) -1L else size
                        return WavFormat(rate, channels, bits, float, body.toLong(), bytes)
                    }
                }
                // Chunks are padded to an even size.
                val next = body + size + (size and 1L)
                if (next > Int.MAX_VALUE) return null
                at = next.toInt()
            }
            return null
        }
    }
}
