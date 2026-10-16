package com.inkwell.notes.speakers.ui

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.inkwell.notes.audio.logic.LevelMeter
import java.io.File

/**
 * Records a short voice sample for enrollment into the cache (excluded from backups) as AAC.
 * The file is the user's voice: callers delete it as soon as the voiceprint is made, and
 * [sweep] removes anything a crash left behind. Main thread.
 */
internal class VoiceSampleRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    val recording: Boolean get() = recorder != null

    /** Starts recording; throws when the microphone can't be opened (busy, no permission). */
    fun start() {
        cancel()
        val dir = dir(context).apply { mkdirs() }
        val f = File.createTempFile("sample", ".m4a", dir)
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            // Voice recognition input: little processing, which suits voiceprints best.
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(SAMPLE_RATE)
            r.setAudioEncodingBitRate(BITRATE)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
        } catch (t: Throwable) {
            runCatching { r.release() }
            f.delete()
            throw t
        }
        recorder = r
        file = f
    }

    /** Current input level 0..1 for the meter. */
    fun level(): Float = runCatching { LevelMeter.normalize(recorder?.maxAmplitude ?: 0) }.getOrDefault(0f)

    /** Stops and returns the sample, or null when nothing usable was written (the file is then deleted). */
    fun stop(): File? {
        val r = recorder ?: return null
        val f = file
        recorder = null
        file = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        if (!ok || f == null || !f.isFile || f.length() == 0L) {
            f?.delete()
            return null
        }
        return f
    }

    /** Stops without keeping anything. */
    fun cancel() {
        stop()?.delete()
    }

    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val BITRATE = 96_000

        private fun dir(context: Context) = File(context.cacheDir, "voice-sample")

        /** Deletes samples left over from an earlier run. */
        fun sweep(context: Context) {
            runCatching { dir(context).listFiles()?.forEach { it.delete() } }
        }
    }
}
