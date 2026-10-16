package com.inkwell.notes.export.format

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Joins a recording's AAC segments into one .m4a without re-encoding: the audio track of
 * each segment is copied sample by sample with its timestamps shifted by the duration of
 * the segments before it. Falls back to a zip of the segments when they cannot be joined
 * (different formats, unreadable file).
 */
internal object AudioJoiner {
    private const val TAG = "AudioJoiner"

    /** Writes [segments] joined into [target] (.m4a). Throws when they cannot be joined. */
    fun join(segments: List<File>, target: File) {
        require(segments.isNotEmpty())
        if (segments.size == 1) {
            segments[0].copyTo(target, overwrite = true)
            return
        }
        target.delete()
        val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            var outTrack = -1
            var firstFormat: MediaFormat? = null
            var offsetUs = 0L
            val buffer = ByteBuffer.allocateDirect(1 shl 20)
            val info = MediaCodec.BufferInfo()
            for (file in segments) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.path)
                    val track = (0 until extractor.trackCount).firstOrNull {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    } ?: throw IOException("No audio track")
                    val format = extractor.getTrackFormat(track)
                    if (firstFormat == null) {
                        firstFormat = format
                        outTrack = muxer.addTrack(format)
                        muxer.start()
                        started = true
                    } else if (!compatible(firstFormat, format)) {
                        throw IOException("Segments use different audio formats")
                    }
                    extractor.selectTrack(track)
                    val sampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
                    val frameUs = 1024L * 1_000_000L / sampleRate.coerceAtLeast(1)
                    var lastUs = -1L
                    var firstUs = -1L
                    while (true) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val t = extractor.sampleTime
                        if (firstUs < 0) firstUs = t
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = offsetUs + (t - firstUs).coerceAtLeast(0)
                        info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        muxer.writeSampleData(outTrack, buffer, info)
                        lastUs = info.presentationTimeUs
                        extractor.advance()
                    }
                    if (lastUs >= 0) offsetUs = lastUs + frameUs
                } finally {
                    extractor.release()
                }
            }
        } catch (t: Throwable) {
            runCatching { if (started) muxer.stop() }
            runCatching { muxer.release() }
            target.delete()
            throw if (t is IOException) t else IOException("Joining failed", t)
        }
        try {
            muxer.stop()
        } catch (e: IllegalStateException) {
            target.delete()
            throw IOException("Joining failed", e)
        } finally {
            muxer.release()
        }
    }

    private fun compatible(a: MediaFormat, b: MediaFormat): Boolean =
        a.getString(MediaFormat.KEY_MIME) == b.getString(MediaFormat.KEY_MIME) &&
            a.intOr(MediaFormat.KEY_SAMPLE_RATE, -1) == b.intOr(MediaFormat.KEY_SAMPLE_RATE, -1) &&
            a.intOr(MediaFormat.KEY_CHANNEL_COUNT, -1) == b.intOr(MediaFormat.KEY_CHANNEL_COUNT, -1)

    private fun MediaFormat.intOr(key: String, fallback: Int): Int = if (containsKey(key)) getInteger(key) else fallback

    /** The segments as a zip (seg_001.m4a ...), used when they cannot be joined. */
    fun zip(segments: List<File>, target: File) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.setLevel(0)
            segments.forEachIndexed { i, f ->
                zip.putNextEntry(ZipEntry("seg_%03d.%s".format(java.util.Locale.ROOT, i + 1, f.extension.ifEmpty { "m4a" })))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Joins when possible, otherwise zips. Returns the file written. */
    fun export(segments: List<File>, dir: File, baseName: String): File {
        val m4a = File(dir, "$baseName.m4a")
        return try {
            join(segments, m4a)
            m4a
        } catch (e: Exception) {
            Log.w(TAG, "Segments not joinable (${e.javaClass.simpleName}); exporting a zip")
            val z = File(dir, "$baseName.zip")
            zip(segments, z)
            z
        }
    }
}
