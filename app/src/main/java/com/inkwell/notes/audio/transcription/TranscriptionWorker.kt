package com.inkwell.notes.audio.transcription

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.audio.logic.TranscriptAssembly
import com.inkwell.notes.audio.logic.TranscriptCheckpoint
import com.inkwell.notes.audio.recorder.AudioFiles
import com.inkwell.notes.core.model.AiException
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.RecordingStatus
import com.inkwell.notes.core.model.TranscriptStatus
import com.inkwell.notes.core.storage.FileStore
import com.inkwell.notes.core.storage.db.TranscriptSegmentEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Transcribes one recording with the user's TRANSCRIPTION model, one audio segment per
 * request. Progress is checkpointed after every segment (transcript.partial.json next to
 * the audio), so a retry after a network failure or a stopped job resumes instead of
 * uploading audio again. On success the transcript replaces any previous one, is indexed
 * for search and handed to sync.
 */
class TranscriptionWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val docId = inputData.getString(Transcriptions.KEY_DOC) ?: return Result.failure()
        val recordingId = inputData.getString(Transcriptions.KEY_RECORDING) ?: return Result.failure()
        val graph = AppGraph.get(applicationContext)
        val recordings = graph.db.recordings()

        val entity = recordings.get(recordingId) ?: return Result.failure(failureData(R.string.audio_no_audio))
        if (entity.status == RecordingStatus.RECORDING || entity.status == RecordingStatus.PAUSED) {
            // Still being recorded (or awaiting recovery): try again later.
            return Result.retry()
        }
        if (!graph.ai.isAvailable(AiTask.TRANSCRIPTION)) {
            recordings.setTranscriptStatus(recordingId, TranscriptStatus.FAILED.name)
            return Result.failure(failureData(R.string.audio_transcribe_unavailable))
        }
        val rec = entity.toModel()
        val files = withContext(Dispatchers.IO) { AudioFiles.segments(graph.files, rec) }
        val durations = rec.segmentDurationsMs
        if (files.isEmpty() || files.none { it.isFile && it.length() > 0 }) {
            recordings.setTranscriptStatus(recordingId, TranscriptStatus.FAILED.name)
            return Result.failure(failureData(R.string.audio_transcribe_no_audio))
        }

        recordings.setTranscriptStatus(recordingId, TranscriptStatus.RUNNING.name)
        val checkpointFile = File(graph.files.audioDir(docId, recordingId), AudioFiles.TRANSCRIPT_CHECKPOINT)
        val paths = files.map { it.absolutePath }
        var checkpoint = withContext(Dispatchers.IO) { readCheckpoint(checkpointFile) }
            ?.takeIf { it.matches(paths) } ?: TranscriptCheckpoint(paths)

        try {
            setProgress(workDataOf(Transcriptions.KEY_DONE to checkpoint.done, Transcriptions.KEY_TOTAL to files.size))
            for (i in checkpoint.done until files.size) {
                val file = files[i]
                val duration = durations.getOrNull(i) ?: 0L
                val pieces = if (!file.isFile || file.length() == 0L) {
                    emptyList()
                } else {
                    val t = graph.ai.transcribe(recordingId, listOf(file), listOf(duration), checkpoint.language)
                        ?: throw AiException(applicationContext.getString(R.string.audio_transcribe_unavailable))
                    if (checkpoint.source == null) checkpoint = checkpoint.copy(source = t.source)
                    if (checkpoint.language == null) checkpoint = checkpoint.copy(language = t.language)
                    TranscriptAssembly.shift(t.segments, TranscriptAssembly.offsetOf(durations, i), duration)
                }
                checkpoint = checkpoint.copy(done = i + 1, segments = checkpoint.segments + pieces)
                val snapshot = checkpoint
                withContext(Dispatchers.IO) { writeCheckpoint(checkpointFile, snapshot) }
                setProgress(workDataOf(Transcriptions.KEY_DONE to i + 1, Transcriptions.KEY_TOTAL to files.size))
            }

            val source = checkpoint.source ?: "openai"
            val segments = checkpoint.segments.sortedBy { it.startMs }
            graph.db.transcripts().replace(
                recordingId,
                segments.map { TranscriptSegmentEntity(recordingId = recordingId, startMs = it.startMs, endMs = it.endMs, text = it.text, speaker = it.speaker, source = source) },
            )
            recordings.setTranscriptStatus(recordingId, TranscriptStatus.DONE.name)
            runCatching { graph.search.replaceTranscript(docId, recordingId, segments, source) }
                .onFailure { Log.w(TAG, "Indexing the transcript failed", it) }
            runCatching { graph.sync.onTranscriptReady(docId, recordingId) }
                .onFailure { Log.w(TAG, "Sync hook failed", it) }
            withContext(Dispatchers.IO) { checkpointFile.delete() }
            return Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                // Stopped by the system: it will run again. Cancelled by the user: the UI already cleared it.
                recordings.setTranscriptStatusIf(recordingId, listOf(TranscriptStatus.RUNNING.name), TranscriptStatus.QUEUED.name)
            }
            throw e
        } catch (e: AiException) {
            Log.w(TAG, "Transcription failed (retryable=${e.retryable})")
            return retryOrFail(graph, recordingId, e.retryable, e.message)
        } catch (e: IOException) {
            Log.w(TAG, "Transcription I/O failure", e)
            return retryOrFail(graph, recordingId, retryable = true, message = null)
        } catch (e: Exception) {
            Log.e(TAG, "Transcription crashed", e)
            return retryOrFail(graph, recordingId, retryable = false, message = null)
        }
    }

    private suspend fun retryOrFail(graph: AppGraph, recordingId: String, retryable: Boolean, message: String?): Result {
        if (retryable && runAttemptCount < MAX_ATTEMPTS) {
            graph.db.recordings().setTranscriptStatus(recordingId, TranscriptStatus.QUEUED.name)
            return Result.retry()
        }
        graph.db.recordings().setTranscriptStatus(recordingId, TranscriptStatus.FAILED.name)
        val text = message?.takeIf { it.isNotBlank() } ?: applicationContext.getString(R.string.audio_transcribe_failed)
        return Result.failure(workDataOf(Transcriptions.KEY_ERROR to text))
    }

    private fun failureData(res: Int) = workDataOf(Transcriptions.KEY_ERROR to applicationContext.getString(res))

    private fun readCheckpoint(file: File): TranscriptCheckpoint? =
        if (!file.isFile) null else runCatching { TranscriptCheckpoint.decode(file.readText()) }.getOrNull()

    private fun writeCheckpoint(file: File, c: TranscriptCheckpoint) {
        runCatching {
            FileStore.writeAtomically(file, keepBackup = false) { it.write(TranscriptCheckpoint.encode(c).toByteArray()) }
        }.onFailure { Log.w(TAG, "Could not save transcription progress", it) }
    }

    private companion object {
        const val TAG = "Transcription"
        const val MAX_ATTEMPTS = 4
    }
}
