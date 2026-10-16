package com.inkwell.notes.audio.recorder

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.audio.RecordingState
import com.inkwell.notes.audio.logic.AudioText
import com.inkwell.notes.audio.logic.RecoveryPlanner
import com.inkwell.notes.audio.logic.Waveform
import com.inkwell.notes.audio.recorder.RecordingSession.Companion.withSegments
import com.inkwell.notes.audio.transcription.Transcriptions
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.RecordingStatus
import com.inkwell.notes.core.model.TranscriptStatus
import com.inkwell.notes.core.storage.db.RecordingEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide recording engine. All commands (start, pause, resume, stop, segment
 * persistence, recorder failures, crash recovery) run one at a time, in order, on a single
 * consumer loop, so switching documents, stopping during a rollover or recovering at launch
 * can never interleave. MediaRecorder lives on its own audio-priority thread; database and
 * file work runs on Dispatchers.IO; state is published through [RecordingRuntime.state].
 *
 * The microphone foreground service ([com.inkwell.notes.audio.RecordingService]) only holds
 * foreground status and the notification; it starts recordings here once it is in the
 * foreground and stops itself when [isIdle].
 */
internal class RecordingEngine(private val graph: AppGraph) {
    fun interface ServiceHooks {
        /** Nothing is recording or about to start; the service may leave the foreground. */
        fun onIdle()
    }

    private sealed interface Command {
        data class Start(val docId: String, val title: String?) : Command
        data class Refused(val message: String) : Command
        data object Pause : Command
        data object Resume : Command
        data class Stop(val error: String? = null, val target: RecordingSession? = null) : Command
        data class SegmentDone(val session: RecordingSession, val index: Int, val file: File) : Command
        data class Failed(val session: RecordingSession, val failure: SegmentRecorder.Failure) : Command
        data class Recover(val done: CompletableDeferred<Unit>) : Command
    }

    private val context: Context = graph.context
    private val state = RecordingRuntime.mutableState
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val pendingStarts = AtomicInteger(0)

    private val thread = HandlerThread("InkwellRecorder", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    private val recorderDispatcher = Handler(thread.looper).asCoroutineDispatcher("InkwellRecorder")

    @Volatile private var session: RecordingSession? = null
    @Volatile private var hooks: ServiceHooks? = null

    private val wakeLock: PowerManager.WakeLock? by lazy {
        runCatching {
            context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Inkwell:recording")
                ?.apply { setReferenceCounted(false) }
        }.getOrNull()
    }

    init {
        graph.appScope.launch(Dispatchers.IO) {
            for (c in commands) {
                try {
                    handle(c)
                } catch (t: Throwable) {
                    Log.e(TAG, "Recording command ${c::class.simpleName} failed", t)
                    if (c is Command.Recover) c.done.complete(Unit)
                }
            }
        }
    }

    /** True when nothing is recording and no start is queued. */
    val isIdle: Boolean get() = session == null && pendingStarts.get() == 0

    val hasSession: Boolean get() = session != null

    fun attach(h: ServiceHooks) { hooks = h }

    fun detach(h: ServiceHooks) { if (hooks === h) hooks = null }

    /** Called by the service once it is in the foreground. */
    fun submitStart(docId: String, title: String?) {
        pendingStarts.incrementAndGet()
        commands.trySend(Command.Start(docId, title))
    }

    /** The service could not enter the foreground; report [message] instead of recording. */
    fun refuseStart(message: String) {
        commands.trySend(Command.Refused(message))
    }

    fun pause() { commands.trySend(Command.Pause) }

    fun resume() { commands.trySend(Command.Resume) }

    fun stop() { commands.trySend(Command.Stop()) }

    suspend fun recover() {
        val done = CompletableDeferred<Unit>()
        commands.send(Command.Recover(done))
        done.await()
    }

    // ------------------------------------------------------------------ command handling

    private suspend fun handle(c: Command) {
        when (c) {
            is Command.Start -> try {
                start(c.docId, c.title)
            } finally {
                pendingStarts.decrementAndGet()
                notifyIfIdle()
            }
            is Command.Refused -> {
                if (session == null) state.value = RecordingState.Error(c.message)
                notifyIfIdle()
            }
            Command.Pause -> session?.let { pause(it) }
            Command.Resume -> session?.let { resume(it) }
            is Command.Stop -> {
                val s = session
                if (s != null && (c.target == null || c.target === s)) finish(s, c.error)
                notifyIfIdle()
            }
            is Command.SegmentDone -> if (c.session === session && !c.session.finishing) persistSegment(c.session, c.index, c.file)
            is Command.Failed -> if (c.session === session) {
                finish(c.session, context.getString(failureMessage(c.failure)))
                notifyIfIdle()
            }
            is Command.Recover -> try {
                recoverAll()
            } finally {
                c.done.complete(Unit)
            }
        }
    }

    private fun notifyIfIdle() {
        if (isIdle) hooks?.onIdle()
    }

    private fun failureMessage(f: SegmentRecorder.Failure): Int = when (f) {
        SegmentRecorder.Failure.MIC_LOST -> R.string.audio_error_mic_lost
        SegmentRecorder.Failure.SERVER_DIED -> R.string.audio_error_server_died
    }

    private suspend fun start(docId: String, title: String?) {
        val current = session
        if (current != null) {
            if (current.docId == docId) {
                if (current.paused) resume(current)
                return
            }
            // One recording at a time: finish the other document's recording first.
            finish(current, error = null)
        }

        val doc = runCatching { graph.library.getDocument(docId) }.getOrNull()
        if (doc == null) {
            state.value = RecordingState.Error(context.getString(R.string.audio_error_doc_missing))
            return
        }
        val now = System.currentTimeMillis()
        val recordingId = UUID.randomUUID().toString()
        val dir = graph.files.audioDir(docId, recordingId)
        val prefs = graph.settings.current.recording
        val config = SegmentRecorder.Config(
            sampleRate = prefs.sampleRate,
            bitrate = prefs.bitrate,
            segmentMs = prefs.segmentSeconds.coerceIn(MIN_SEGMENT_SECONDS, MAX_SEGMENT_SECONDS) * 1000L,
        )
        val n = runCatching { graph.db.recordings().countForDocument(docId) }.getOrDefault(0) + 1
        val name = title?.trim()?.takeIf { it.isNotEmpty() } ?: AudioText.defaultTitle(n, now)
        val entity = RecordingEntity(
            id = recordingId, documentId = docId, title = name, segmentFiles = "", segmentDurations = "",
            durationMs = 0, startedAt = now, endedAt = null, status = RecordingStatus.RECORDING,
            transcriptStatus = TranscriptStatus.NONE, waveformPath = AudioFiles.waveformFile(dir).absolutePath,
        )
        try {
            graph.db.recordings().upsert(entity)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not create the recording row", t)
            dir.deleteRecursively()
            state.value = RecordingState.Error(context.getString(R.string.audio_error_start))
            return
        }

        val s = RecordingSession(docId, recordingId, now, dir, config, SystemClock::elapsedRealtime)
        s.docTitle = doc.title
        val started = runCatching {
            withContext(recorderDispatcher) {
                val rec = SegmentRecorder(context, config, { AudioFiles.segmentFile(dir, it) }, listenerFor(s))
                rec.start(0)
                s.recorder = rec
                s.begin()
            }
        }
        if (started.isFailure) {
            Log.w(TAG, "Recorder failed to start", started.exceptionOrNull())
            runCatching { graph.db.recordings().delete(recordingId) }
            dir.deleteRecursively()
            state.value = RecordingState.Error(context.getString(R.string.audio_error_start))
            return
        }
        session = s
        RecordingRuntime.session = s
        acquireWakeLock()
        state.value = s.state()
        s.pollJob = graph.appScope.launch(recorderDispatcher) { poll(s) }
    }

    private fun listenerFor(s: RecordingSession) = object : SegmentRecorder.Listener {
        override fun onSegmentFinished(index: Int, file: File) {
            // Recorder thread: book the boundary now so the timeline stays exact, persist on the loop.
            val ledgerIndex = s.roll(file)
            commands.trySend(Command.SegmentDone(s, ledgerIndex, file))
        }

        override fun onRecorderError(failure: SegmentRecorder.Failure) {
            commands.trySend(Command.Failed(s, failure))
        }
    }

    /** Recorder thread: level meter, waveform envelope and the long-segment safety net. */
    private suspend fun poll(s: RecordingSession) {
        while (currentCoroutineContext().isActive && !s.finishing) {
            delay(POLL_MS)
            if (s.paused || s.finishing) continue
            val rec = s.recorder ?: continue
            s.sample(rec.maxAmplitude())
            if (rec.isRunning && s.currentSegmentMs() > s.config.segmentMs * FORCE_ROLL_FACTOR) {
                rec.forceRoll()
            }
            if (!s.finishing && session === s) state.value = s.state()
        }
    }

    private suspend fun pause(s: RecordingSession) {
        if (s.paused || s.finishing) return
        val ok = runCatching { withContext(recorderDispatcher) { s.recorder?.pause() } }
        if (ok.isFailure) {
            Log.w(TAG, "Pause failed", ok.exceptionOrNull())
            return
        }
        s.markPaused()
        state.value = s.state()
        releaseWakeLock()
        runCatching { graph.db.recordings().mutate(s.recordingId) { it.copy(status = RecordingStatus.PAUSED) } }
        runCatching { AudioFiles.writeWaveform(s.waveformFile, s.waveformBytes()) }
    }

    private suspend fun resume(s: RecordingSession) {
        if (!s.paused || s.finishing) return
        val ok = runCatching { withContext(recorderDispatcher) { s.recorder?.resume() } }
        if (ok.isFailure) {
            Log.w(TAG, "Resume failed", ok.exceptionOrNull())
            finish(s, context.getString(R.string.audio_error_mic_lost))
            return
        }
        s.markResumed()
        acquireWakeLock()
        state.value = s.state()
        runCatching { graph.db.recordings().mutate(s.recordingId) { it.copy(status = RecordingStatus.RECORDING) } }
    }

    /** A finished segment: measure it, save the envelope, and record it so a crash keeps it. */
    private suspend fun persistSegment(s: RecordingSession, index: Int, file: File) {
        AudioFiles.durationMs(file)?.let { s.correct(index, it) }
        runCatching { AudioFiles.writeWaveform(s.waveformFile, s.waveformBytes()) }
            .onFailure { Log.w(TAG, "Waveform save failed", it) }
        val segments = s.segments()
        val updated = try {
            graph.db.recordings().mutate(s.recordingId) { it.withSegments(segments) }
        } catch (t: Throwable) {
            Log.e(TAG, "Saving segment $index failed", t)
            return
        }
        if (updated == null) {
            // The row (or its document) was deleted while recording.
            finish(s, context.getString(R.string.audio_error_doc_missing))
        }
    }

    /**
     * Stops [s], keeps every readable segment, writes the envelope and completes the row.
     * State goes Idle (or Error with [error]) as soon as the microphone is released.
     */
    private suspend fun finish(s: RecordingSession, error: String?) {
        if (s.finishing) return
        s.finishing = true
        runCatching { s.pollJob?.cancelAndJoin() }
        val stopped = runCatching {
            withContext(recorderDispatcher) {
                val r = s.recorder
                s.recorder = null
                r?.stop()
            }
        }.getOrNull()
        s.stopClock()
        session = null
        RecordingRuntime.session = null
        releaseWakeLock()
        state.value = if (error != null) RecordingState.Error(error) else RecordingState.Idle

        // The segment being written when we stopped.
        val last = stopped?.file
        if (last != null && s.segments().none { it.path == last.absolutePath }) {
            val ms = AudioFiles.durationMs(last)
            if (ms != null) s.correct(s.roll(last), ms) else last.delete()
        }
        // Segments whose measurement failed at rollover get another try.
        for ((i, seg) in s.segments().withIndex()) {
            if (!seg.measured) AudioFiles.durationMs(File(seg.path))?.let { s.correct(i, it) }
        }

        val segments = s.segments().filter { File(it.path).let { f -> f.isFile && f.length() > 0 } }
        if (segments.isEmpty()) {
            runCatching { graph.db.recordings().delete(s.recordingId) }
            s.dir.deleteRecursively()
            return
        }
        val total = segments.sumOf { it.durationMs }
        runCatching { AudioFiles.writeWaveform(s.waveformFile, Waveform.trim(s.waveformBytes(), total)) }
            .onFailure { Log.w(TAG, "Waveform save failed", it) }
        val endedAt = System.currentTimeMillis()
        val updated = try {
            graph.db.recordings().mutate(s.recordingId) {
                it.withSegments(segments).copy(status = RecordingStatus.COMPLETE, endedAt = endedAt)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Completing the recording failed", t)
            if (error == null) state.value = RecordingState.Error(context.getString(R.string.audio_error_save))
            return
        }
        if (updated == null) {
            s.dir.deleteRecursively()
            return
        }
        afterFinished(s.docId, s.recordingId)
    }

    private suspend fun afterFinished(docId: String, recordingId: String) {
        runCatching { graph.sync.onRecordingFinished(docId, recordingId) }
            .onFailure { Log.w(TAG, "Sync hook failed", it) }
        val auto = graph.settings.current.recording.autoTranscribe
        if (auto && runCatching { graph.ai.isAvailable(AiTask.TRANSCRIPTION) }.getOrDefault(false)) {
            runCatching { Transcriptions.enqueue(graph, docId, recordingId, replace = false) }
                .onFailure { Log.w(TAG, "Could not queue transcription", it) }
        }
    }

    // ------------------------------------------------------------------ crash recovery

    private suspend fun recoverAll() {
        val activeId = session?.recordingId
        val unfinished = runCatching { graph.db.recordings().unfinished() }.getOrDefault(emptyList())
        for (e in unfinished) {
            if (e.id == activeId) continue
            runCatching { recoverOne(e) }.onFailure { Log.e(TAG, "Recovering a recording failed", it) }
        }
    }

    private suspend fun recoverOne(e: RecordingEntity) {
        val rec = e.toModel()
        val dir = graph.files.audioDir(e.documentId, e.id)
        val persisted = HashMap<String, Long>()
        rec.segmentFiles.forEachIndexed { i, p ->
            val f = AudioFiles.resolve(graph.files, e.documentId, e.id, p)
            persisted[f.absolutePath] = rec.segmentDurationsMs.getOrNull(i) ?: 0L
        }
        val onDisk = dir.listFiles()?.filter { RecoveryPlanner.indexOf(it.name) != null }.orEmpty()
        val candidates = (persisted.keys.map { File(it) } + onDisk).distinctBy { it.absolutePath }
        val probes = candidates.mapIndexed { i, f ->
            RecoveryPlanner.Probe(
                path = f.absolutePath,
                index = RecoveryPlanner.indexOf(f.name) ?: i,
                sizeBytes = if (f.isFile) f.length() else 0L,
                measuredMs = AudioFiles.durationMs(f),
                persistedMs = persisted[f.absolutePath],
            )
        }
        val plan = RecoveryPlanner.plan(probes)
        for (p in plan.dropped) {
            val f = File(p)
            if (f.parentFile?.absolutePath == dir.absolutePath) f.delete()
        }
        if (plan.kept.isEmpty()) {
            graph.db.recordings().delete(e.id)
            dir.deleteRecursively()
            return
        }
        val waveFile = AudioFiles.waveformFile(dir)
        AudioFiles.readWaveform(waveFile)?.let { bytes ->
            runCatching { AudioFiles.writeWaveform(waveFile, Waveform.trim(bytes, plan.totalMs)) }
        }
        val endedAt = plan.kept.maxOf { File(it.path).lastModified() }.takeIf { it > 0 } ?: (e.startedAt + plan.totalMs)
        val updated = graph.db.recordings().mutate(e.id) {
            it.copy(
                title = AudioText.recoveredTitle(it.title),
                segmentFiles = plan.paths.joinToString("\n"),
                segmentDurations = plan.durations.joinToString(","),
                durationMs = plan.totalMs,
                endedAt = endedAt,
                status = RecordingStatus.INTERRUPTED,
                waveformPath = waveFile.absolutePath,
            )
        }
        if (updated != null) {
            runCatching { graph.sync.onRecordingFinished(e.documentId, e.id) }
        }
    }

    // ------------------------------------------------------------------ wake lock

    private fun acquireWakeLock() {
        runCatching { wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
    }

    companion object {
        private const val TAG = "RecordingEngine"
        const val POLL_MS = 50L
        private const val MIN_SEGMENT_SECONDS = 15
        private const val MAX_SEGMENT_SECONDS = 1800
        /** A segment this many times longer than intended is closed by restarting the recorder. */
        private const val FORCE_ROLL_FACTOR = 2.5
        /** Re-acquired on every resume; bounds the damage if a release is ever missed. */
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000
    }
}
