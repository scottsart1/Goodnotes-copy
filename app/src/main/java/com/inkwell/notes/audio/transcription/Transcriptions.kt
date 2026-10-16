package com.inkwell.notes.audio.transcription

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.TranscriptStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** Queues, cancels and observes background transcription of recordings (WorkManager). */
internal object Transcriptions {
    const val KEY_DOC = "docId"
    const val KEY_RECORDING = "recordingId"
    const val KEY_DONE = "done"
    const val KEY_TOTAL = "total"
    const val KEY_ERROR = "error"
    /** Non-fatal note of a finished job (e.g. transcribed without the speaker labels that were asked for). */
    const val KEY_SPEAKER_NOTE = "speakerNote"
    private const val TAG = "inkwell-transcription"

    fun uniqueName(recordingId: String) = "inkwell-transcribe-$recordingId"

    /**
     * Queues transcription of [recordingId]. [replace] restarts a queued or failed job
     * (explicit "Transcribe" / "Try again"); otherwise an existing job is kept.
     */
    suspend fun enqueue(graph: AppGraph, docId: String, recordingId: String, replace: Boolean) {
        graph.db.recordings().setTranscriptStatus(recordingId, TranscriptStatus.QUEUED.name)
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(workDataOf(KEY_DOC to docId, KEY_RECORDING to recordingId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(graph.context).enqueueUniqueWork(
            uniqueName(recordingId),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Cancels a queued or running transcription. The status goes back to what the row has:
     * an earlier transcript (a cancelled re-transcription keeps it) or nothing.
     */
    suspend fun cancel(graph: AppGraph, recordingId: String) {
        WorkManager.getInstance(graph.context).cancelUniqueWork(uniqueName(recordingId))
        val keepsTranscript = runCatching { graph.db.transcripts().forRecording(recordingId).isNotEmpty() }.getOrDefault(false)
        graph.db.recordings().setTranscriptStatusIf(
            recordingId,
            listOf(TranscriptStatus.QUEUED.name, TranscriptStatus.RUNNING.name),
            (if (keepsTranscript) TranscriptStatus.DONE else TranscriptStatus.NONE).name,
        )
    }

    fun observe(context: Context, recordingId: String): Flow<WorkInfo?> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(uniqueName(recordingId))
            // After a REPLACE the cancelled job can still be listed next to the new one.
            .map { infos -> infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull() }

    /** Progress (done, total) reported by a running job, or null. */
    fun progressOf(info: WorkInfo?): Pair<Int, Int>? {
        if (info == null || info.state != WorkInfo.State.RUNNING) return null
        val total = info.progress.getInt(KEY_TOTAL, 0)
        if (total <= 0) return null
        return info.progress.getInt(KEY_DONE, 0) to total
    }

    /** User-facing failure message of a finished job, or null. */
    fun errorOf(info: WorkInfo?): String? =
        if (info?.state == WorkInfo.State.FAILED) info.outputData.getString(KEY_ERROR) else null

    /** The speaker note of a job that succeeded, or null. */
    fun speakerNoteOf(info: WorkInfo?): String? =
        if (info?.state == WorkInfo.State.SUCCEEDED) info.outputData.getString(KEY_SPEAKER_NOTE)?.takeIf { it.isNotBlank() } else null
}
