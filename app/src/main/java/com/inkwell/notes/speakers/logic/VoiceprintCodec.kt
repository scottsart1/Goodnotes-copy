package com.inkwell.notes.speakers.logic

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One stored voice vector: a person's sample, a recording voice's centroid, ... */
class VoiceVector(
    /** What it came from, e.g. "rec:<recordingId>:S2", "enroll:<time>" or a voice label. */
    val tag: String,
    /** Speech behind it in ms (how much to trust it when averaging). */
    val weight: Float,
    val vector: FloatArray,
) {
    override fun equals(other: Any?): Boolean =
        other is VoiceVector && other.tag == tag && other.weight == weight && other.vector.contentEquals(vector)

    override fun hashCode(): Int = (tag.hashCode() * 31 + weight.hashCode()) * 31 + vector.contentHashCode()

    override fun toString(): String = "VoiceVector($tag, $weight, dim=${vector.size})"
}

/** A set of voice vectors from one embedding model. */
data class Voiceprint(
    /** Embedding model the vectors come from; vectors of another model are not comparable. */
    val modelId: String,
    val dim: Int,
    val vectors: List<VoiceVector>,
)

/**
 * Versioned binary format of voiceprint files (encrypted before they touch the disk):
 *
 *   "IKVP" magic, u8 version (1), u16 length + UTF-8 model id, i32 dim, i32 count,
 *   then per vector: u16 length + UTF-8 tag, f32 weight, dim x f32 -- all little-endian.
 *
 * Pure.
 */
object VoiceprintCodec {
    const val VERSION = 1
    private val MAGIC = byteArrayOf('I'.code.toByte(), 'K'.code.toByte(), 'V'.code.toByte(), 'P'.code.toByte())
    const val MAX_DIM = 4096
    const val MAX_VECTORS = 4096

    fun encode(print: Voiceprint): ByteArray {
        require(print.dim in 1..MAX_DIM) { "Bad dimension" }
        require(print.vectors.size <= MAX_VECTORS) { "Too many vectors" }
        val model = print.modelId.toByteArray(Charsets.UTF_8)
        require(model.size <= 0xFFFF)
        val tags = print.vectors.map { it.tag.toByteArray(Charsets.UTF_8).also { t -> require(t.size <= 0xFFFF) } }
        val size = 4 + 1 + 2 + model.size + 4 + 4 + tags.sumOf { 2 + it.size + 4 + print.dim * 4 }
        val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(MAGIC).put(VERSION.toByte())
        bb.putShort(model.size.toShort()).put(model)
        bb.putInt(print.dim).putInt(print.vectors.size)
        for ((i, v) in print.vectors.withIndex()) {
            require(v.vector.size == print.dim) { "Vector $i has dimension ${v.vector.size}, expected ${print.dim}" }
            bb.putShort(tags[i].size.toShort()).put(tags[i])
            bb.putFloat(v.weight)
            for (x in v.vector) bb.putFloat(x)
        }
        return bb.array()
    }

    /** Null when [bytes] is not a voiceprint of a known version, or is truncated or inconsistent. */
    fun decode(bytes: ByteArray): Voiceprint? = try {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { bb.get(it) }
        if (!magic.contentEquals(MAGIC)) {
            null
        } else if (bb.get().toInt() != VERSION) {
            null
        } else {
            val model = string(bb)
            val dim = bb.getInt()
            val count = bb.getInt()
            if (dim !in 1..MAX_DIM || count !in 0..MAX_VECTORS) {
                null
            } else {
                val vectors = ArrayList<VoiceVector>(count)
                repeat(count) {
                    val tag = string(bb)
                    val weight = bb.getFloat()
                    val v = FloatArray(dim) { bb.getFloat() }
                    if (v.all { it.isFinite() } && weight.isFinite()) vectors += VoiceVector(tag, weight, v)
                }
                if (bb.hasRemaining()) null else Voiceprint(model, dim, vectors)
            }
        }
    } catch (_: BufferUnderflowException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun string(bb: ByteBuffer): String {
        val n = bb.getShort().toInt() and 0xFFFF
        val b = ByteArray(n)
        bb.get(b)
        return String(b, Charsets.UTF_8)
    }
}

/** A person's reference clip: 16 kHz mono WAV and how much speech it holds. */
class ReferenceClip(val speechMs: Long, val wav: ByteArray) {
    override fun equals(other: Any?): Boolean = other is ReferenceClip && other.speechMs == speechMs && other.wav.contentEquals(wav)
    override fun hashCode(): Int = speechMs.hashCode() * 31 + wav.contentHashCode()
}

/** "IKRF" magic, u8 version (1), i64 speech ms, i32 length, WAV bytes; little-endian. Pure. */
object ReferenceClipCodec {
    const val VERSION = 1
    private val MAGIC = byteArrayOf('I'.code.toByte(), 'K'.code.toByte(), 'R'.code.toByte(), 'F'.code.toByte())

    fun encode(clip: ReferenceClip): ByteArray {
        val bb = ByteBuffer.allocate(4 + 1 + 8 + 4 + clip.wav.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(MAGIC).put(VERSION.toByte()).putLong(clip.speechMs).putInt(clip.wav.size).put(clip.wav)
        return bb.array()
    }

    fun decode(bytes: ByteArray): ReferenceClip? = try {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { bb.get(it) }
        if (!magic.contentEquals(MAGIC) || bb.get().toInt() != VERSION) {
            null
        } else {
            val speech = bb.getLong()
            val n = bb.getInt()
            if (n < 0 || n != bb.remaining()) null else ReferenceClip(speech, ByteArray(n).also { bb.get(it) })
        }
    } catch (_: BufferUnderflowException) {
        null
    }
}
