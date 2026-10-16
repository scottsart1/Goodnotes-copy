package com.inkwell.notes.speakers

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.audio.recorder.AudioFiles
import com.inkwell.notes.speakers.logic.AudioPlan
import com.inkwell.notes.speakers.logic.FloatBuffer
import com.inkwell.notes.speakers.logic.Pcm
import com.inkwell.notes.speakers.logic.Resampler
import com.inkwell.notes.speakers.logic.WavFormat
import com.inkwell.notes.speakers.logic.WavWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder

/**
 * Decodes recording audio to 16 kHz mono float PCM: AAC/M4A segments (and anything else the
 * platform reads) with MediaExtractor + MediaCodec, WAV directly. Channels are averaged and the
 * rate converted with a band-limited [Resampler], streaming, so memory holds only the 16 kHz
 * result. Runs on Dispatchers.IO; never on the main thread.
 */
class SpeakerAudioImpl(private val graph: AppGraph) : SpeakerAudio {

    override suspend fun decode(file: File, startMs: Long, endMs: Long): FloatArray = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() == 0L || endMs <= startMs) return@withContext FloatArray(0)
        val start = startMs.coerceAtLeast(0)
        val head = ByteArray(HEAD_BYTES)
        val headLen = RandomAccessFile(file, "r").use { it.read(head) }.coerceAtLeast(0)
        val wav = WavFormat.parse(head.copyOf(headLen))
        if (wav != null) decodeWav(file, wav, start, endMs) else decodeCompressed(file, start, endMs)
    }

    override suspend fun clip(recordingId: String, startMs: Long, endMs: Long): ByteArray? {
        val samples = timeline(recordingId, startMs, minOf(endMs, startMs + MAX_CLIP_MS)) ?: return null
        return withContext(Dispatchers.Default) { WavWriter.encode(samples, SpeakerAudio.SAMPLE_RATE) }
    }

    /**
     * 16 kHz audio of [startMs, endMs) of a recording's timeline, spanning segment files; missing
     * or unreadable stretches are silence so the result stays aligned. Null when the recording
     * is unknown or nothing could be decoded.
     */
    internal suspend fun timeline(recordingId: String, startMs: Long, endMs: Long): FloatArray? {
        val rec = graph.db.recordings().get(recordingId)?.toModel() ?: return null
        val files = withContext(Dispatchers.IO) { AudioFiles.segments(graph.files, rec) }
        val pieces = AudioPlan.slice(rec.segmentDurationsMs, startMs.coerceAtLeast(0), endMs)
        if (pieces.isEmpty()) return null
        val out = FloatBuffer(Pcm.samplesFor(endMs - startMs, SpeakerAudio.SAMPLE_RATE).coerceIn(16, 16 * 1024 * 1024))
        var decodedAny = false
        for (p in pieces) {
            val expected = Pcm.samplesFor(p.toMs, SpeakerAudio.SAMPLE_RATE) - Pcm.samplesFor(p.fromMs, SpeakerAudio.SAMPLE_RATE)
            val f = files.getOrNull(p.segment)
            val pcm = if (f != null) decodeOrNull(f, p.fromMs, p.toMs) else null
            if (pcm != null && pcm.isNotEmpty()) decodedAny = true
            out.add(Pcm.fitTo(pcm ?: FloatArray(0), expected))
        }
        return if (decodedAny) out.toArray() else null
    }

    /** [decode], with unreadable files reported as null (cancellation still propagates). */
    internal suspend fun decodeOrNull(file: File, startMs: Long, endMs: Long): FloatArray? = try {
        decode(file, startMs, endMs)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Could not decode an audio segment: ${e.javaClass.simpleName}")
        null
    }

    // ------------------------------------------------------------------ WAV

    private suspend fun decodeWav(file: File, fmt: WavFormat, startMs: Long, endMs: Long): FloatArray {
        val align = fmt.blockAlign
        if (align <= 0) return FloatArray(0)
        RandomAccessFile(file, "r").use { raf ->
            val dataEnd = if (fmt.dataBytes >= 0) minOf(raf.length(), fmt.dataOffset + fmt.dataBytes) else raf.length()
            val totalFrames = ((dataEnd - fmt.dataOffset).coerceAtLeast(0)) / align
            val firstFrame = (startMs * fmt.sampleRate / 1000).coerceAtMost(totalFrames)
            val lastFrame = if (endMs == Long.MAX_VALUE) totalFrames else (endMs * fmt.sampleRate / 1000).coerceIn(firstFrame, totalFrames)
            val resampler = Resampler(fmt.sampleRate, SpeakerAudio.SAMPLE_RATE)
            val out = FloatBuffer(resampler.outputLength(lastFrame - firstFrame).coerceIn(16L, Int.MAX_VALUE.toLong()).toInt())
            val chunkFrames = (CHUNK_BYTES / align).coerceAtLeast(1)
            val buf = ByteArray(chunkFrames * align)
            raf.seek(fmt.offsetOfFrame(firstFrame))
            var frame = firstFrame
            while (frame < lastFrame) {
                currentCoroutineContext().ensureActive()
                val want = (minOf(chunkFrames.toLong(), lastFrame - frame) * align).toInt()
                val n = raf.read(buf, 0, want)
                if (n <= 0) break
                val mono = fmt.toMono(buf, n - n % align)
                out.add(resampler.process(mono))
                frame += n / align
            }
            out.add(resampler.flush())
            return out.toArray()
        }
    }

    // ------------------------------------------------------------------ compressed (AAC/M4A, ...)

    private suspend fun decodeCompressed(file: File, startMs: Long, endMs: Long): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return FloatArray(0)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return FloatArray(0)
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            val startUs = startMs * 1000
            val endUs = if (endMs >= Long.MAX_VALUE / 1000) Long.MAX_VALUE else endMs * 1000
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var rate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 0)
            var channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
            var floatPcm = false
            var resampler: Resampler? = null
            val out = FloatBuffer()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                var progressed = false
                if (!inputDone) {
                    val inIndex = c.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = c.getInputBuffer(inIndex)
                        val n = if (buf == null) -1 else extractor.readSampleData(buf, 0)
                        val t = extractor.sampleTime
                        if (n < 0 || (endUs != Long.MAX_VALUE && t > endUs + LOOKAHEAD_US)) {
                            c.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIndex, 0, n, t.coerceAtLeast(0), 0)
                            extractor.advance()
                        }
                        progressed = true
                    }
                }
                val outIndex = c.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        val newRate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, rate)
                        channels = f.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                        floatPcm = f.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_FLOAT
                        if (newRate != rate) {
                            resampler?.let { out.add(it.flush()) }
                            resampler = null
                            rate = newRate
                        }
                        progressed = true
                    }
                    outIndex >= 0 -> {
                        progressed = true
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val buf = c.getOutputBuffer(outIndex)
                        if (buf != null && info.size > 0 && rate > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val mono = if (floatPcm) {
                                val fb = buf.order(ByteOrder.nativeOrder()).asFloatBuffer()
                                val arr = FloatArray(fb.remaining()).also { fb.get(it) }
                                Pcm.downmixFloat(arr, arr.size, channels)
                            } else {
                                val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                                val arr = ShortArray(sb.remaining()).also { sb.get(it) }
                                Pcm.downmix16(arr, arr.size, channels)
                            }
                            // Keep only frames inside [startUs, endUs).
                            val pts = info.presentationTimeUs
                            val from = if (pts >= startUs) 0 else ((startUs - pts) * rate / 1_000_000).coerceAtMost(mono.size.toLong()).toInt()
                            val to = if (endUs == Long.MAX_VALUE) mono.size else ((endUs - pts) * rate / 1_000_000).coerceIn(0, mono.size.toLong()).toInt()
                            if (to > from) {
                                val r = resampler ?: Resampler(rate, SpeakerAudio.SAMPLE_RATE).also { resampler = it }
                                out.add(r.process(mono, from, to - from))
                            }
                            if (endUs != Long.MAX_VALUE && pts + mono.size * 1_000_000L / rate >= endUs) {
                                c.releaseOutputBuffer(outIndex, false)
                                break
                            }
                        }
                        c.releaseOutputBuffer(outIndex, false)
                        if (eos) break
                    }
                }
                idle = if (progressed) 0 else idle + 1
                if (idle > MAX_IDLE_LOOPS) {
                    Log.w(TAG, "Decoder stalled; keeping what was decoded")
                    break
                }
            }
            resampler?.let { out.add(it.flush()) }
            return out.toArray()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

    private companion object {
        const val TAG = "SpeakerAudio"
        const val HEAD_BYTES = 64 * 1024
        const val CHUNK_BYTES = 256 * 1024
        const val TIMEOUT_US = 10_000L

        /** Input past the end is still fed this far, so the decoder flushes the last frames. */
        const val LOOKAHEAD_US = 200_000L

        /** ~5 s without input or output progress: the decoder is stuck. */
        const val MAX_IDLE_LOOPS = 250

        /** Clips are for listening and references; longer requests are cut. */
        const val MAX_CLIP_MS = 15L * 60 * 1000
    }
}
