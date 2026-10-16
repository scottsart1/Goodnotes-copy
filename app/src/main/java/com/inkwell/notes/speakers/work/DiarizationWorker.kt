package com.inkwell.notes.speakers.work

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.audio.recorder.AudioFiles
import com.inkwell.notes.core.model.RecordingStatus
import com.inkwell.notes.core.model.SpeakerEngine
import com.inkwell.notes.core.storage.FileStore
import com.inkwell.notes.speakers.SpeakerAudio
import com.inkwell.notes.speakers.SpeakerAudioImpl
import com.inkwell.notes.speakers.SpeakerServiceImpl
import com.inkwell.notes.speakers.engine.SherpaVoiceEngine
import com.inkwell.notes.speakers.logic.AnalysisWindow
import com.inkwell.notes.speakers.logic.AudioPlan
import com.inkwell.notes.speakers.logic.DiarizationAssembler
import com.inkwell.notes.speakers.logic.DiarizationCheckpoint
import com.inkwell.notes.speakers.logic.FloatBuffer
import com.inkwell.notes.speakers.logic.Pcm
import com.inkwell.notes.speakers.logic.SpeakerModelSpecs
import com.inkwell.notes.speakers.logic.VoiceVector
import com.inkwell.notes.speakers.logic.Voiceprint
import com.inkwell.notes.speakers.logic.WindowAnalyzer
import com.inkwell.notes.speakers.voices.valueOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Works out who spoke when in one recording, on the device (engine DEVICE), after it was
 * recorded. The timeline is cut into windows of up to ten minutes of consecutive segments;
 * each window is decoded, diarized and embedded on a background-priority thread, then
 * checkpointed (turns in speakers.partial.json next to the audio, window embeddings only in
 * the encrypted voice store), so a stopped job resumes at the next window. Finally the
 * windows' voices are linked into the recording's voices and handed to
 * [SpeakerServiceImpl.saveDiarization], which recognises known people and labels the
 * transcript. When the models are missing it queues their download and ends; it is queued
 * again when they arrive.
 */
class DiarizationWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val docId = inputData.getString(Diarizations.KEY_DOC) ?: return Result.failure()
        val recordingId = inputData.getString(Diarizations.KEY_RECORDING) ?: return Result.failure()
        val graph = AppGraph.get(applicationContext)
        val service = graph.speakers as? SpeakerServiceImpl ?: return Result.failure()
        val prefs = graph.settings.current.speakers
        if (prefs.engine != SpeakerEngine.DEVICE) return failure(R.string.speaker_engine_error_off)

        val entity = graph.db.recordings().get(recordingId) ?: return failure(R.string.speaker_engine_error_no_audio)
        if (entity.status == RecordingStatus.RECORDING || entity.status == RecordingStatus.PAUSED) return Result.retry()
        val rec = entity.toModel()

        if (!service.models.present() || !service.models.verifyOnce()) {
            if (service.models.present()) {
                // Present but unreadable (I/O error): downloading again would not help.
                return retryOrFail()
            }
            service.awaitModels(docId, recordingId)
            // Arrived meanwhile: run again shortly instead of waiting for a download that is done.
            if (service.models.present()) return Result.retry()
            return Result.success(workDataOf(Diarizations.KEY_WAITING to true))
        }

        val files = withContext(Dispatchers.IO) { AudioFiles.segments(graph.files, rec) }
        val available = withContext(Dispatchers.IO) { files.map { it.isFile && it.length() > 0 } }
        val windows = AudioPlan.windows(rec.segmentDurationsMs, available)
        if (windows.isEmpty()) return failure(R.string.speaker_engine_error_no_audio)

        val paths = files.map { it.absolutePath }
        val checkpointFile = File(graph.files.audioDir(docId, recordingId), CHECKPOINT)
        val remember = prefs.rememberVoices
        val resumed = if (remember) loadCheckpoint(service, checkpointFile, recordingId, paths, rec.segmentDurationsMs, windows.size) else null
        var checkpoint = resumed?.first
            ?: DiarizationCheckpoint(modelId = SpeakerModelSpecs.EMBEDDING_MODEL_ID, files = paths, durations = rec.segmentDurationsMs)
        val embeddings = HashMap<String, FloatArray>(resumed?.second.orEmpty())
        if (resumed == null) withContext(Dispatchers.IO) { service.store.deleteWork(recordingId) }

        val audio = graph.speakerAudio as? SpeakerAudioImpl
        val dispatcher = SherpaVoiceEngine.newDispatcher()
        var engine: SherpaVoiceEngine? = null
        try {
            engine = withContext(dispatcher) {
                SherpaVoiceEngine(service.models.file(SpeakerModelSpecs.SEGMENTATION), service.models.file(SpeakerModelSpecs.EMBEDDING))
            }
            report(windows, checkpoint.windowsDone, 0f, force = true)
            for (w in windows.drop(checkpoint.windowsDone)) {
                val samples = decodeWindow(graph, audio, files, w)
                val e = engine
                val result = withContext(dispatcher) {
                    WindowAnalyzer.analyze(
                        windowIndex = w.index,
                        globalStartMs = w.globalStartMs,
                        samples = samples,
                        engine = e,
                        progress = { f -> report(windows, w.index, f, force = false) },
                        checkCancelled = { if (isStopped) throw CancellationException("stopped") },
                    )
                }
                embeddings.putAll(result.embeddings)
                checkpoint = checkpoint.copy(
                    windowsDone = w.index + 1,
                    turns = checkpoint.turns + result.turns,
                    voices = checkpoint.voices + result.voices,
                )
                if (remember) saveCheckpoint(service, checkpointFile, recordingId, checkpoint, embeddings)
                report(windows, w.index + 1, 0f, force = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkageError) {
            // The native library could not load (unsupported CPU or a broken install).
            Log.e(TAG, "Speaker models unavailable", e)
            return failure(R.string.speaker_engine_error_unsupported)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory while identifying speakers")
            return retryOrFail()
        } catch (e: Exception) {
            Log.e(TAG, "Identification failed", e)
            return retryOrFail()
        } finally {
            withContext(NonCancellable + dispatcher) { engine?.close() }
            dispatcher.close()
        }

        val final = checkpoint
        val result = withContext(Dispatchers.Default) {
            DiarizationAssembler.assemble(
                recordingId = recordingId,
                engineId = SpeakerModelSpecs.ENGINE_ID,
                turns = final.turns,
                voices = final.voices,
                embeddings = embeddings,
                totalMs = rec.durationMs.takeIf { it > 0 } ?: rec.segmentDurationsMs.sum(),
                expectedSpeakers = graph.settings.current.speakers.expectedSpeakers.coerceAtLeast(0),
            )
        }
        return try {
            service.saveDiarization(docId, result)
            withContext(Dispatchers.IO) {
                checkpointFile.delete()
                service.store.deleteWork(recordingId)
            }
            runCatching { service.notifyIfNamesNeeded(docId, recordingId) }.onFailure { Log.w(TAG, "Naming notification failed", it) }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Saving the speakers failed", e)
            retryOrFail()
        }
    }

    /** Decodes one window's pieces into contiguous 16 kHz audio aligned with the timeline. */
    private suspend fun decodeWindow(graph: AppGraph, audio: SpeakerAudioImpl?, files: List<File>, w: AnalysisWindow): FloatArray {
        val out = FloatBuffer(Pcm.samplesFor(w.durationMs, SpeakerAudio.SAMPLE_RATE).coerceAtLeast(16))
        for (p in w.pieces) {
            val expected = Pcm.samplesFor(p.toMs, SpeakerAudio.SAMPLE_RATE) - Pcm.samplesFor(p.fromMs, SpeakerAudio.SAMPLE_RATE)
            val file = files.getOrNull(p.segment)
            val pcm = when {
                file == null -> null
                audio != null -> audio.decodeOrNull(file, p.fromMs, p.toMs)
                else -> runCatching { graph.speakerAudio.decode(file, p.fromMs, p.toMs) }.getOrNull()
            }
            out.add(Pcm.fitTo(pcm ?: FloatArray(0), expected))
        }
        return out.toArray()
    }

    @Volatile private var lastReport = 0L

    /** Progress in segments (pieces) done; throttled, callable from the engine thread. */
    private fun report(windows: List<AnalysisWindow>, windowsDone: Int, fraction: Float, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastReport < PROGRESS_MS) return
        lastReport = now
        val (done, total) = DiarizationAssembler.progress(windows, windowsDone, fraction)
        setProgressAsync(workDataOf(Diarizations.KEY_DONE to done, Diarizations.KEY_TOTAL to total))
    }

    /** A checkpoint that matches this recording, with its window embeddings; null to start over. */
    private suspend fun loadCheckpoint(
        service: SpeakerServiceImpl,
        file: File,
        recordingId: String,
        paths: List<String>,
        durations: List<Long>,
        windows: Int,
    ): Pair<DiarizationCheckpoint, Map<String, FloatArray>>? = withContext(Dispatchers.IO) {
        val c = runCatching { if (file.isFile) DiarizationCheckpoint.decode(file.readText()) else null }.getOrNull()
            ?.takeIf { it.matches(SpeakerModelSpecs.EMBEDDING_MODEL_ID, paths, durations, windows) }
            ?: return@withContext null
        val stored = service.store.readWork(recordingId).valueOrNull()
        val embeddings = stored?.vectors?.associate { it.tag to it.vector }.orEmpty()
        if (c.consistentWith(embeddings.keys)) c to embeddings else null
    }

    private suspend fun saveCheckpoint(
        service: SpeakerServiceImpl,
        file: File,
        recordingId: String,
        c: DiarizationCheckpoint,
        embeddings: Map<String, FloatArray>,
    ) = withContext(Dispatchers.IO) {
        try {
            // Embeddings first: a checkpoint never claims embeddings that were not stored.
            val dim = embeddings.values.firstOrNull()?.size
            if (dim != null) {
                val weights = c.voices.associate { it.id to it.speechMs.toFloat() }
                service.store.writeWork(
                    recordingId,
                    Voiceprint(SpeakerModelSpecs.EMBEDDING_MODEL_ID, dim, embeddings.map { (id, v) -> VoiceVector(id, weights[id] ?: 0f, v) }),
                )
            }
            FileStore.writeAtomically(file, keepBackup = false) { it.write(DiarizationCheckpoint.encode(c).toByteArray()) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not save identification progress: ${e.javaClass.simpleName}")
        }
    }

    private fun failure(res: Int) = Result.failure(workDataOf(Diarizations.KEY_ERROR to applicationContext.getString(res)))

    private fun retryOrFail(): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else failure(R.string.speaker_engine_error_failed)

    private companion object {
        const val TAG = "Diarization"
        const val CHECKPOINT = "speakers.partial.json"
        const val MAX_ATTEMPTS = 3
        const val PROGRESS_MS = 500L
    }
}
