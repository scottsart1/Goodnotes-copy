package com.inkwell.notes.ai.provider

import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.speakers.SpeakerReference
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A known voice as sent to gpt-4o-transcribe-diarize: a unique, non-empty name and a 2-10 s WAV clip. */
class DiarizeReference(val name: String, val wav: ByteArray)

/**
 * The rules OpenAI enforces on known speaker references, applied before anything is sent so a
 * bad clip never costs the transcript: at most [MAX_REFERENCES], names unique and non-empty,
 * clips between [MIN_CLIP_MS] and [MAX_CLIP_MS] (longer clips are trimmed to their middle,
 * shorter or unreadable ones dropped).
 */
object DiarizeReferences {
    const val MAX_REFERENCES = 4
    const val MIN_CLIP_MS = 2_000L
    const val MAX_CLIP_MS = 10_000L
    const val MAX_NAME_CHARS = 64

    fun prepare(references: List<SpeakerReference>): List<DiarizeReference> {
        val out = ArrayList<DiarizeReference>(MAX_REFERENCES)
        val names = HashSet<String>()
        for (r in references) {
            if (out.size == MAX_REFERENCES) break
            val name = cleanName(r.name) ?: continue
            val key = name.lowercase()
            if (key in names) continue
            val wav = fitClip(r.wav) ?: continue
            names += key
            out += DiarizeReference(name, wav)
        }
        return out
    }

    /** Single-line, trimmed, capped; null when nothing is left. */
    fun cleanName(raw: String): String? =
        raw.replace(Regex("\\s+"), " ").trim().take(MAX_NAME_CHARS).trim().takeIf { it.isNotEmpty() }

    /** [wav] when it is a readable 2-10 s clip, its middle 10 s when longer, else null. */
    fun fitClip(wav: ByteArray): ByteArray? {
        val info = WavClip.parse(wav) ?: return null
        val ms = info.durationMs
        return when {
            ms < MIN_CLIP_MS -> null
            ms <= MAX_CLIP_MS -> wav
            else -> WavClip.middle(wav, info, MAX_CLIP_MS)
        }
    }
}

/** Minimal RIFF/WAVE reader for reference clips: the format, where the samples are and how long they last. */
object WavClip {
    data class Info(
        val format: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        /** Byte offset of the first sample. */
        val dataOffset: Int,
        /** Sample bytes actually present (a streamed header's oversized length is clamped). */
        val dataSize: Int,
    ) {
        val blockAlign: Int get() = (channels * bitsPerSample / 8).coerceAtLeast(1)
        val byteRate: Int get() = sampleRate * blockAlign
        val durationMs: Long get() = if (byteRate <= 0) 0 else dataSize.toLong() * 1000 / byteRate
    }

    fun parse(bytes: ByteArray): Info? {
        if (bytes.size < 12 || tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WAVE") return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var format = -1
        var channels = 0
        var rate = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = tag(bytes, pos)
            val size = buf.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            val body = pos + 8
            if (id == "fmt " && size >= 16 && body + 16 <= bytes.size) {
                format = buf.getShort(body).toInt() and 0xFFFF
                channels = buf.getShort(body + 2).toInt() and 0xFFFF
                rate = buf.getInt(body + 4)
                bits = buf.getShort(body + 14).toInt() and 0xFFFF
            } else if (id == "data") {
                if (format !in SUPPORTED_FORMATS || channels <= 0 || rate <= 0 || bits <= 0 || bits % 8 != 0) return null
                val available = (bytes.size - body).toLong()
                val size32 = minOf(size, available).toInt()
                val aligned = size32 - size32 % (channels * bits / 8)
                return Info(format, channels, rate, bits, body, aligned)
            }
            // Chunks are word aligned.
            val next = body.toLong() + size + (size and 1L)
            if (next > Int.MAX_VALUE) return null
            pos = next.toInt()
        }
        return null
    }

    /** The middle [maxMs] of a clip as a canonical 44-byte-header WAV. */
    fun middle(bytes: ByteArray, info: Info, maxMs: Long): ByteArray {
        val keep = ((info.byteRate.toLong() * maxMs / 1000) / info.blockAlign * info.blockAlign).toInt().coerceAtMost(info.dataSize)
        val skip = ((info.dataSize - keep) / 2) / info.blockAlign * info.blockAlign
        return build(info, bytes, info.dataOffset + skip, keep)
    }

    private fun build(info: Info, src: ByteArray, from: Int, length: Int): ByteArray {
        // WAVE_FORMAT_EXTENSIBLE carries its sample type in an extension; the trimmed copy keeps plain PCM/float codes.
        val format = if (info.format == FORMAT_EXTENSIBLE) (if (info.bitsPerSample == 32) FORMAT_FLOAT else FORMAT_PCM) else info.format
        val out = ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + length).put("WAVE".toByteArray(Charsets.US_ASCII))
        out.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
            .putShort(format.toShort()).putShort(info.channels.toShort())
            .putInt(info.sampleRate).putInt(info.byteRate)
            .putShort(info.blockAlign.toShort()).putShort(info.bitsPerSample.toShort())
        out.put("data".toByteArray(Charsets.US_ASCII)).putInt(length)
        out.put(src, from, length)
        return out.array()
    }

    private fun tag(b: ByteArray, at: Int): String =
        if (at + 4 > b.size) "" else String(b, at, 4, Charsets.US_ASCII)

    private const val FORMAT_PCM = 1
    private const val FORMAT_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE
    private val SUPPORTED_FORMATS = setOf(FORMAT_PCM, FORMAT_FLOAT, FORMAT_EXTENSIBLE)
}

/**
 * gpt-4o-transcribe-diarize `diarized_json` responses → transcript blocks on the recording
 * timeline, each with the provider's speaker label (a reference name, or "A", "B", ... local
 * to the file). Tolerant of missing fields, numbers sent as strings and unknown item types.
 */
object DiarizedTranscriptionParser {
    data class Parsed(
        val segments: List<TranscriptSegment>,
        val language: String?,
        val text: String,
        /** Audio length the provider reported, in ms (0 when absent). */
        val durationMs: Long,
        val inputTokens: Int?,
        val outputTokens: Int?,
    )

    fun parse(body: String, offsetMs: Long, durationMs: Long): Parsed {
        val json = parseObject(body)
        if (json == null) {
            val t = body.trim()
            val seg = if (t.isEmpty()) emptyList() else listOf(TranscriptSegment(offsetMs, offsetMs + durationMs.coerceAtLeast(0), t))
            return Parsed(seg, null, t, 0, null, null)
        }
        val text = json["text"].str()?.trim().orEmpty()
        val reportedMs = seconds(json["duration"])?.let { Math.round(it * 1000) }?.coerceAtLeast(0) ?: 0L
        val limit = if (durationMs > 0) offsetMs + durationMs else Long.MAX_VALUE
        val items = json["segments"].arr().orEmpty().mapNotNull { it.obj() }
        var cursor = 0.0
        val segments = ArrayList<TranscriptSegment>(items.size)
        for (s in items) {
            val type = s["type"].str()
            if (type != null && !type.contains("segment")) continue
            val t = s["text"].str()?.trim().orEmpty()
            val start = seconds(s["start"])?.coerceAtLeast(0.0) ?: cursor
            val end = (seconds(s["end"]) ?: start).coerceAtLeast(start)
            cursor = end
            if (t.isEmpty()) continue
            val startMs = (offsetMs + Math.round(start * 1000)).coerceAtMost(limit)
            val endMs = (offsetMs + Math.round(end * 1000)).coerceIn(startMs, limit.coerceAtLeast(startMs))
            segments += TranscriptSegment(startMs, endMs, t, speaker = label(s["speaker"]))
        }
        if (segments.isEmpty() && text.isNotEmpty()) {
            val dur = durationMs.takeIf { it > 0 } ?: reportedMs
            segments += TranscriptSegment(offsetMs, offsetMs + dur, text)
        }
        segments.sortBy { it.startMs }
        val usage = json["usage"].obj()
        return Parsed(
            segments = segments,
            language = json["language"].str(),
            text = text,
            durationMs = reportedMs.takeIf { it > 0 } ?: usage?.let { u -> seconds(u["seconds"])?.let { Math.round(it * 1000) } } ?: 0L,
            inputTokens = usage?.let { number(it["input_tokens"])?.toInt() },
            outputTokens = usage?.let { number(it["output_tokens"])?.toInt() },
        )
    }

    private fun label(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun seconds(e: JsonElement?): Double? = number(e)?.takeIf { it.isFinite() }

    private fun number(e: JsonElement?): Double? = (e as? JsonPrimitive)?.contentOrNull?.trim()?.toDoubleOrNull()
}
