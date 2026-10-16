package com.inkwell.notes.audio.recorder

import com.inkwell.notes.audio.RecordingState
import com.inkwell.notes.audio.logic.LevelMeter
import com.inkwell.notes.audio.logic.PausableClock
import com.inkwell.notes.audio.logic.SegmentLedger
import com.inkwell.notes.audio.logic.WaveformAccumulator
import com.inkwell.notes.core.storage.db.RecordingEntity
import android.media.AudioManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * One recording in progress. Its clock, ledger and envelope are touched from the recorder
 * thread (level polling, segment rollover), the engine loop (persistence) and the UI thread
 * (audio anchors), so every access goes through [lock].
 */
internal class RecordingSession(
    val docId: String,
    val recordingId: String,
    val startedAt: Long,
    val dir: File,
    val config: SegmentRecorder.Config,
    elapsedRealtime: () -> Long,
) {
    private val lock = Any()
    private val clock = PausableClock(elapsedRealtime)
    private val ledger = SegmentLedger()
    private val envelope = WaveformAccumulator()

    @Volatile var paused: Boolean = false
        private set
    @Volatile var level: Float = 0f
        private set
    /** Set once stopping begins: no more anchors, samples or state updates. */
    @Volatile var finishing: Boolean = false
    @Volatile var docTitle: String = ""

    /** Recorder-thread only. */
    var recorder: SegmentRecorder? = null
    var pollJob: Job? = null
    var recordingCallback: AudioManager.AudioRecordingCallback? = null
    var polls: Long = 0

    /** elapsedRealtime when the system started silencing our capture (another app took the mic); 0 = not silenced. */
    @Volatile var silencedSince: Long = 0L
    /** A failure was already reported; don't report it again from the poll loop. */
    @Volatile var failureReported: Boolean = false

    val waveformFile: File get() = AudioFiles.waveformFile(dir)

    fun begin() = synchronized(lock) { clock.resume() }

    fun markPaused() = synchronized(lock) {
        clock.pause()
        paused = true
        level = 0f
    }

    fun markResumed() = synchronized(lock) {
        clock.resume()
        paused = false
        // Silencing reported while paused says nothing about the resumed capture.
        silencedSince = 0L
    }

    fun stopClock() = synchronized(lock) { clock.pause() }

    /** One amplitude poll: updates the meter level and the waveform envelope. */
    fun sample(amplitude: Int) = synchronized(lock) {
        if (paused || finishing) return@synchronized
        val target = LevelMeter.normalize(amplitude)
        level = LevelMeter.smooth(level, target)
        envelope.add(target, clock.elapsedMs())
    }

    /** Position of "now" on the recording's playback timeline. */
    fun timelineMs(): Long = synchronized(lock) { ledger.timelineMs(clock.elapsedMs()) }

    fun currentSegmentMs(): Long = synchronized(lock) { ledger.currentSegmentMs(clock.elapsedMs()) }

    /** Records that the current segment was finalised into [file]; returns its index. */
    fun roll(file: File): Int = synchronized(lock) { ledger.roll(file.absolutePath, clock.elapsedMs()) }

    fun correct(index: Int, measuredMs: Long) = synchronized(lock) { ledger.correct(index, measuredMs) }

    fun segments(): List<SegmentLedger.Segment> = synchronized(lock) { ledger.finished }

    fun waveformBytes(): ByteArray = synchronized(lock) { envelope.snapshot() }

    fun state(): RecordingState.Active = synchronized(lock) { snapshot() }

    /**
     * Publishes the current state to [target]. Snapshot and assignment happen under the
     * session lock, so a level poll can never overwrite a newer pause/resume with stale data.
     */
    fun publish(target: MutableStateFlow<RecordingState>) = synchronized(lock) {
        if (!finishing) target.value = snapshot()
    }

    private fun snapshot() = RecordingState.Active(
        docId = docId,
        recordingId = recordingId,
        startedAt = startedAt,
        elapsedMs = ledger.timelineMs(clock.elapsedMs()),
        paused = paused,
        level = if (paused) 0f else level,
    )

    companion object {
        /** The row with [segments] as its audio. */
        fun RecordingEntity.withSegments(segments: List<SegmentLedger.Segment>): RecordingEntity = copy(
            segmentFiles = segments.joinToString("\n") { it.path },
            segmentDurations = segments.joinToString(",") { it.durationMs.toString() },
            durationMs = segments.sumOf { it.durationMs },
        )
    }
}
